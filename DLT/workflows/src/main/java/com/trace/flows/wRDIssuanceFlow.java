package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.contracts.wRDContract;
import com.trace.states.UtilAccountState;
import com.trace.states.wRDAccountState;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.AbstractParty;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import org.jetbrains.annotations.NotNull;

import java.security.PublicKey;
import java.util.*;
import java.util.stream.Collectors;

@InitiatingFlow
@StartableByRPC
public class wRDIssuanceFlow extends FlowLogic<SignedTransaction> {

    private final UniqueIdentifier sourceWalletId;
    private final UniqueIdentifier receiverWalletId;
    private final Amount<Currency> amount;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD issuance.");
    private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
    private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.Companion.tracker();
        }
    };
    private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.Companion.tracker();
        }
    };

    private final ProgressTracker progressTracker = new ProgressTracker(
            GENERATING_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
    );

    public wRDIssuanceFlow(UniqueIdentifier sourceWalletId, UniqueIdentifier receiverWalletId,
                           Amount<Currency> amount) {
        this.sourceWalletId = sourceWalletId;
        this.receiverWalletId = receiverWalletId;
        this.amount = amount;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
        // 1. Define all parties involved
        // 1.1 Notary node
        final Party notary = getServiceHub().getNetworkMapCache().getNotary(CordaX500Name.parse("O=Notary,L=Jakarta," +
                "C=ID"));
        if (notary == null) {
            throw new IllegalArgumentException("Notary should be not null.");
        }

        final Party kdr = getOurIdentity();
        // We will validate KDR node early since next step will costly to operate between nodes (to get states
        // that may have different version, it will be used to build transaction later)
        if (!kdr.getName().getOrganisation().equals("KDR")) {
            throw new FlowException("Source party should be KDR.");
        }
        // 1.2 We will try to get receiver node based on owner (party - wholesaler) of receiverWallet
        QueryCriteria criteria = getQueryCriteria();

        // Before get receiverParty we need to query source and receiver wallet states inside source party
        List<StateAndRef<wRDAccountState>> stateAndRefOfSourceAndReceiverWalletStatesInSourceParty =
                getServiceHub().getVaultService().queryBy(wRDAccountState.class, criteria).getStates();

        List<StateAndRef<wRDAccountState>> stateAndRefSourceWalletStates = new ArrayList<>();
        List<StateAndRef<wRDAccountState>> stateAndRefReceiverWalletStates = new ArrayList<>();

        Party receiverParty = null;

        boolean isSourceWalletStateExistInKDR = false;
        boolean isReceiverWalletStateExistInKDR = false;
        for (StateAndRef<wRDAccountState> stateAndRef : stateAndRefOfSourceAndReceiverWalletStatesInSourceParty) {
            if (stateAndRef.getState().getData().getWalletId().equals(sourceWalletId)) {
                if (!stateAndRef.getState().getData().getOwner().equals(kdr)) {
                    throw new IllegalArgumentException("Source wallet state must be owned by source party (KDR)");
                }
                isSourceWalletStateExistInKDR = true;
                stateAndRefSourceWalletStates.add(stateAndRef);
            }
            if (stateAndRef.getState().getData().getWalletId().equals(receiverWalletId)) {
                if (stateAndRef.getState().getData().getOwner().equals(kdr)) {
                    throw new IllegalArgumentException("Receiver wallet state must be owned by non-kdr party.");
                }
                isReceiverWalletStateExistInKDR = true;
                stateAndRefReceiverWalletStates.add(stateAndRef);
                receiverParty = stateAndRef.getState().getData().getOwner();
            }
        }

        if (!isSourceWalletStateExistInKDR) {
            throw new IllegalArgumentException("Source wallet state must be exist in source party (KDR)");
        }

        // TODO: Adjust this conditional check if implement multi node KDR
        // We simplified this logic by assume only one KDR node, which is who initialize every wholesaler wallet.
        // Therefore, every wholesaler (receiver) wallet state must be already stored in KDR node.
        if (!isReceiverWalletStateExistInKDR || receiverParty == null) {
            // Consider KDR is node who issuance all wholesaler wallet states.
            throw new FlowException("Receiver wallet state should be stored in source party (KDR)");
        }

        // 2. Gather source and receiver wallet states inside receiver party
        // Here why (common challenge in DLT): the receiver wallet state in source party not updated yet, we don't
        // know if there is another transaction between receiver with other party. Therefore, the receiver wallet
        // inside the receiver party need to be retrieved, then we can compare which receiver wallet updated (based
        // on the highest version).
        // TODO: Adjust party to every KDR node if implement multi node for KDR
        FlowSession session = initiateFlow(receiverParty);
        session.send(new UtilAccountState.StateSyncRequest(sourceWalletId, receiverWalletId));
        // This receive() method will BLOCK the flow until get the response.
        UtilAccountState.StateSyncResponse response = session.receive(UtilAccountState.StateSyncResponse.class).unwrap(data -> data);
        if (response.getStateAndRefOfSourceWalletState() != null) {
            stateAndRefSourceWalletStates.add(response.getStateAndRefOfSourceWalletState());
        }
        if (response.getStateAndRefOfReceiverWalletState() == null) {
            throw new IllegalArgumentException("Receiver wallet must be exist inside receiver party");
        }
        stateAndRefReceiverWalletStates.add(response.getStateAndRefOfReceiverWalletState());

        UtilAccountState.StateSyncDetermined determinedStates = determineSourceAndReceiverWalletStates(stateAndRefSourceWalletStates,
                stateAndRefReceiverWalletStates);

        StateAndRef<wRDAccountState> stateAndRefSourceWalletStateDetermined =
                determinedStates.getStateAndRefSourceWalletStateDetermined();
        StateAndRef<wRDAccountState> stateAndRefReceiverWalletStateDetermined =
                determinedStates.getStateAndRefReceiverWalletStateDetermined();

        // 3. Now we ready to build transaction
        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        final TransactionBuilder txBuilder = new TransactionBuilder(notary);
        // 3.1 Determine the input (StateAndRef)
        txBuilder.addInputState(stateAndRefSourceWalletStateDetermined);
        txBuilder.addInputState(stateAndRefReceiverWalletStateDetermined);
        // 3.2 Determine the output states
        if (stateAndRefSourceWalletStateDetermined.getState().getData().getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance for source wallet account (KDR).");
        }
        Amount<Currency> remainingBalanceResourceWallet =
                stateAndRefSourceWalletStateDetermined.getState().getData().getTokenBalance().minus(amount);
        wRDAccountState outputSourceWalletState = stateAndRefSourceWalletStateDetermined.getState().getData()
                .withNewBalanceAndIssuer(remainingBalanceResourceWallet, receiverParty);

        Amount<Currency> remainingBalanceReceiverWallet =
                stateAndRefReceiverWalletStateDetermined.getState().getData().getTokenBalance().plus(amount);
        wRDAccountState outputReceiverWalletState = stateAndRefReceiverWalletStateDetermined.getState().getData()
                .withNewBalanceAndIssuer(remainingBalanceReceiverWallet, kdr);

        txBuilder.addOutputState(outputSourceWalletState, wRDContract.ID);
        txBuilder.addOutputState(outputReceiverWalletState, wRDContract.ID);

        // 3.3 Add command related with wRDIssuanceFlow
        List<PublicKey> listOfRequiredSignersInSourceInputState =
                stateAndRefSourceWalletStateDetermined.getState().getData().getParticipants()
                        .stream().map(AbstractParty::getOwningKey)
                        .collect(Collectors.toList());
        List<PublicKey> listOfRequiredSignersInReceiverOutputState =
                stateAndRefReceiverWalletStateDetermined.getState().getData().getParticipants()
                        .stream().map(AbstractParty::getOwningKey)
                        .collect(Collectors.toList());

        List<PublicKey> mergedListOfRequiredSigners = new ArrayList<>();
        mergedListOfRequiredSigners.addAll(listOfRequiredSignersInSourceInputState);
        mergedListOfRequiredSigners.addAll(listOfRequiredSignersInReceiverOutputState);

        txBuilder.addCommand(new wRDContract.Commands.wRDIssuanceCommand(), mergedListOfRequiredSigners);

        // 4. Verify the transaction based on wRD Contract verify method (in current / source party)
        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        // 5. Sign the transaction (in current / source party)
        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        // 6. Collect all the required signatures from other nodes
        // This CollectSignaturesFlow will trigger SignTransactionFlow in responder, that already called on step 2 above
        progressTracker.setCurrentStep(GATHERING_SIGS);
        List<FlowSession> sessions = new ArrayList<>();
        sessions.add(session);
        for (AbstractParty participant : stateAndRefSourceWalletStateDetermined.getState().getData().getParticipants()) {
            Party partyToInitiateFlow = (Party) participant;
            if (!partyToInitiateFlow.getOwningKey().equals(getOurIdentity().getOwningKey())
                    && !partyToInitiateFlow.getOwningKey().equals(receiverParty.getOwningKey())) {
                FlowSession sessionOnlySign = initiateFlow(partyToInitiateFlow);
                // Simple approach to bypass blocking receive syncStatRequest in responder flow
                sessionOnlySign.send("SIGN_ONLY");
                sessions.add(sessionOnlySign);
            }
        }
        for (AbstractParty participant : stateAndRefReceiverWalletStateDetermined.getState().getData().getParticipants()) {
            Party partyToInitiateFlow = (Party) participant;
            if (!partyToInitiateFlow.getOwningKey().equals(getOurIdentity().getOwningKey())
                    && !partyToInitiateFlow.getOwningKey().equals(receiverParty.getOwningKey())) {
                FlowSession sessionOnlySign = initiateFlow(partyToInitiateFlow);
                // Simple approach to bypass blocking receive syncStatRequest in responder flow
                sessionOnlySign.send("SIGN_ONLY");
                sessions.add(sessionOnlySign);
            }
        }
        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx,
                sessions, GATHERING_SIGS.childProgressTracker()));

        // 7. Return the output of the FinalityFlow which sends the transaction to the notary for verification and
        // the causes it to be persisted to the vault of appropriate nodes.
        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, sessions,
                FINALISING_TRANSACTION.childProgressTracker()));

        // 8. Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));

        return finalTx;
    }

    @NotNull
    private QueryCriteria getQueryCriteria() {
        List<java.util.UUID> walletIds = Arrays.asList(sourceWalletId.getId(), receiverWalletId.getId());
        QueryCriteria walletIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
        QueryCriteria statusStateCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        return statusStateCriteria.and(walletIdCriteria);
    }

    @NotNull
    private UtilAccountState.StateSyncDetermined determineSourceAndReceiverWalletStates(
            List<StateAndRef<wRDAccountState>> stateAndRefSourceWalletStates,
            List<StateAndRef<wRDAccountState>> stateAndRefReceiverWalletStates
    ) {
        if (stateAndRefSourceWalletStates.isEmpty() || stateAndRefReceiverWalletStates.isEmpty()) {
            throw new IllegalArgumentException("Unable determine with source wallet states ("
                    + stateAndRefSourceWalletStates.size()
                    + ") and receiver wallet states ("
                    + stateAndRefReceiverWalletStates.size() + ")");
        }

        StateAndRef<wRDAccountState> stateAndRefSourceWalletStateDetermined = stateAndRefSourceWalletStates.get(0);
        if (stateAndRefSourceWalletStates.size() > 1) {
            stateAndRefSourceWalletStateDetermined = stateAndRefSourceWalletStates.stream()
                    .max(Comparator.comparing(stateAndRef -> stateAndRef.getState().getData().getVersion()))
                    .orElseThrow(() -> new IllegalArgumentException("No source wallet state to be determined."));
        }

        StateAndRef<wRDAccountState> stateAndRefReceiverWalletStateDetermined = stateAndRefReceiverWalletStates.get(0);
        if (stateAndRefReceiverWalletStates.size() > 1) {
            stateAndRefReceiverWalletStateDetermined = stateAndRefReceiverWalletStates.stream()
                    .max(Comparator.comparing(stateAndRef -> stateAndRef.getState().getData().getVersion()))
                    .orElseThrow(() -> new IllegalArgumentException("No receiver wallet state found to be determined."));
        }

        return new UtilAccountState.StateSyncDetermined(stateAndRefSourceWalletStateDetermined,
                stateAndRefReceiverWalletStateDetermined);
    }
}

@InitiatedBy(wRDIssuanceFlow.class)
class wRDIssuanceFlowResponder extends FlowLogic<SignedTransaction> {

    private final FlowSession counterpartySession;
    private SecureHash txRespSignedId;

    private final ProgressTracker.Step RESP_GET_SIGN = new ProgressTracker.Step("Gathering responder sign tx.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return SignTransactionFlow.Companion.tracker();
        }
    };

    public wRDIssuanceFlowResponder(FlowSession counterpartySession) {
        this.counterpartySession = counterpartySession;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
        // Fix case: there are other signers party non-receiver party. It means the source party not send
        // StateSyncRequest object for those signers instead only SignedTransaction object (from source party).
        Object receivedObject = counterpartySession.receive(Object.class).unwrap(data -> data);
        if (receivedObject instanceof UtilAccountState.StateSyncRequest) {
            // 1. Receive sync request with sourceWalletId and receiveWalletId
            UtilAccountState.StateSyncRequest request = (UtilAccountState.StateSyncRequest) receivedObject;

            // 2. Get source wallet state inside receiver party (this node)
            QueryCriteria criteriaSourceWalletState = getQueryCriteria(request.getSourceWalletId());
            QueryCriteria criteriaReceiverWalletState = getQueryCriteria(request.getReceiverWalletId());

            List<StateAndRef<wRDAccountState>> stateAndRefOfSourceWalletStatesInReceiverParty =
                    getServiceHub().getVaultService().queryBy(wRDAccountState.class, criteriaSourceWalletState).getStates();
            List<StateAndRef<wRDAccountState>> stateAndRefOfReceiverWalletStatesInReceiverParty =
                    getServiceHub().getVaultService().queryBy(wRDAccountState.class, criteriaReceiverWalletState).getStates();

            // Note: the source wallet inside receiver wallet can be null (already consumed by other transaction).
            if (stateAndRefOfReceiverWalletStatesInReceiverParty.size() != 1) {
                throw new FlowException("Invalid number of receiver wallet ("
                        + stateAndRefOfReceiverWalletStatesInReceiverParty.size()
                        + ") inside receiver party.");
            }

            UtilAccountState.StateSyncResponse response = new UtilAccountState.StateSyncResponse(
                    (stateAndRefOfSourceWalletStatesInReceiverParty.isEmpty()) ?
                            null : stateAndRefOfSourceWalletStatesInReceiverParty.get(0),
                    stateAndRefOfReceiverWalletStatesInReceiverParty.get(0)
            );

            // 3. Send source wallet and receive wallet states as respond to source party.
            // These states will be used to determine which wallet states with the highest version (most updated).
            counterpartySession.send(response);
            // Note: this send() method non-blocking type, so it will immediately continue to the next step below
        }

        class SignTxFlow extends SignTransactionFlow {
            private SignTxFlow(FlowSession counterPartySession, ProgressTracker progressTracker) {
                super(counterPartySession, progressTracker);
            }

            @Override
            protected void checkTransaction(@NotNull SignedTransaction stx) {
                // Add verify steps here
                txRespSignedId = stx.getId();
            }
        }

        /*
         Based on steps in this initiator flow, we should stop to step 3 above (send response).
         No need to continue to step 4 below. However since send() is non-blocking method then
         Step 4 below still will be occurred. Fortunately the SignTransactionFlow using built in
         receive() method that will be blocking until CollectSignaturesFlow executed (what we want).
         see: https://github.com/corda/corda/blob/1514141f23abc7386f9e187c6d48750773d808a6/core/src/main/kotlin/net/corda/core/flows/CollectSignaturesFlow.kt#L281
        */
        // 4. Create a sign transaction flow
        SignTxFlow signTxFlow = new SignTxFlow(counterpartySession, RESP_GET_SIGN.childProgressTracker());

        // 5. Run the sign transaction flows to sign the transaction
        subFlow(signTxFlow);

        // 6. Run the ReceiveFinalityFlow to finalize the transaction and persist it to the vault.
        return subFlow(new ReceiveFinalityFlow(counterpartySession, txRespSignedId));
    }

    @NotNull
    private QueryCriteria getQueryCriteria(UniqueIdentifier walletId) {
        List<java.util.UUID> walletIds = Arrays.asList(walletId.getId());
        QueryCriteria walletIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
        QueryCriteria statusStateCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        return statusStateCriteria.and(walletIdCriteria);
    }
}