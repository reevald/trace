package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.contracts.RDContract;
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
public class wRDTransferFlow extends FlowLogic<SignedTransaction> {
    private final UniqueIdentifier sourceWalletId;
    private final UniqueIdentifier receiverWalletId;
    private final Party receiverWholesaler;
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

    public wRDTransferFlow(UniqueIdentifier sourceWalletId, UniqueIdentifier receiverWalletId,
                           Party receiverWholesaler, Amount<Currency> amount){
        this.sourceWalletId = sourceWalletId;
        this.receiverWalletId = receiverWalletId;
        // Note: the wholesaler used to minimize process searching the wholesaler owner of the wallet.
        this.receiverWholesaler = receiverWholesaler;
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

        // 1.2 Source wholesaler (is current node, who initiate this flow)
        final Party sourceWholesaler = getOurIdentity();
        // This flow dedicated for transfer between wholesaler (non KDR type)
        // TODO: adjust this if implement multi node KDR
        if (sourceWholesaler.getName().getOrganisation().equals("KDR")) {
            throw new FlowException("Source party should be not KDR.");
        }

        // 2. Gather source and receiver wallet states inside source party
        QueryCriteria criteria = getQueryCriteria();
        List<StateAndRef<wRDAccountState>> stateAndRefOfSourceAndReceiverWalletStatesInSourceParty =
                getServiceHub().getVaultService().queryBy(wRDAccountState.class, criteria).getStates();

        List<StateAndRef<wRDAccountState>> stateAndRefSourceWalletStates = new ArrayList<>();
        List<StateAndRef<wRDAccountState>> stateAndRefReceiverWalletStates = new ArrayList<>();

        boolean isSourceWalletStateExistInSourceParty = false;
        for (StateAndRef<wRDAccountState> stateAndRef : stateAndRefOfSourceAndReceiverWalletStatesInSourceParty) {
            if (stateAndRef.getState().getData().getWalletId().equals(sourceWalletId)) {
                if (!stateAndRef.getState().getData().getOwner().equals(sourceWholesaler)) {
                    throw new IllegalArgumentException("Source wallet states must be owned by source party.");
                }
                isSourceWalletStateExistInSourceParty = true;
                stateAndRefSourceWalletStates.add(stateAndRef);
            }

            if (stateAndRef.getState().getData().getWalletId().equals(receiverWalletId)) {
                if (!stateAndRef.getState().getData().getOwner().equals(receiverWholesaler)) {
                    throw new IllegalArgumentException("Receiver wallet state must be owned by receiver party.");
                }
                stateAndRefReceiverWalletStates.add(stateAndRef);
            }
        }

        if (!isSourceWalletStateExistInSourceParty) {
            throw new IllegalArgumentException("Source wallet state must be exist in source party.");
        }

        // 3. Gather source and receiver wallet states inside receiver party
        FlowSession session = initiateFlow(receiverWholesaler);
        session.send(new UtilAccountState.StateSyncRequest(sourceWalletId, receiverWalletId));

        UtilAccountState.StateSyncResponse response =
                session.receive(UtilAccountState.StateSyncResponse.class).unwrap(data -> data);
        if (response.getStateAndRefOfSourceWalletState() != null) {
            stateAndRefSourceWalletStates.add(response.getStateAndRefOfSourceWalletState());
        }
        stateAndRefReceiverWalletStates.add(response.getStateAndRefOfReceiverWalletState());

        UtilAccountState.StateSyncDetermined determinedStates =
                determineSourceAndReceiverWalletStates(stateAndRefSourceWalletStates, stateAndRefReceiverWalletStates);

        StateAndRef<wRDAccountState> stateAndRefSourceWalletStateDetermined =
                determinedStates.getStateAndRefSourceWalletStateDetermined();
        StateAndRef<wRDAccountState> stateAndRefReceiverWalletStateDetermined =
                determinedStates.getStateAndRefReceiverWalletStateDetermined();

        // 4. Generating transaction blueprint
        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        final TransactionBuilder txBuilder = new TransactionBuilder(notary);
        // 4.1 Determine the input (StakeAndRef)
        txBuilder.addInputState(stateAndRefSourceWalletStateDetermined);
        txBuilder.addInputState(stateAndRefReceiverWalletStateDetermined);
        // 4.2 Determine the output states
        if (stateAndRefSourceWalletStateDetermined.getState().getData()
                .getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance for source wallet state.");
        }

        Amount<Currency> remainingBalanceResourceWallet =
                stateAndRefSourceWalletStateDetermined.getState().getData().getTokenBalance().minus(amount);
        wRDAccountState outputSourceWalletState =
                stateAndRefSourceWalletStateDetermined.getState().getData()
                        .withNewBalanceAndIssuer(remainingBalanceResourceWallet, receiverWholesaler);

        Amount<Currency> remainingBalanceReceiverWallet =
                stateAndRefReceiverWalletStateDetermined.getState().getData().getTokenBalance().plus(amount);
        wRDAccountState outputReceiverWalletState =
                stateAndRefReceiverWalletStateDetermined.getState().getData()
                        .withNewBalanceAndIssuer(remainingBalanceReceiverWallet, sourceWholesaler);

        txBuilder.addOutputState(outputSourceWalletState, RDContract.ID);
        txBuilder.addOutputState(outputReceiverWalletState, RDContract.ID);

        // 4.3 Add command related with wRDTransferFlow
        List<PublicKey> listOfRequiredSignersInSourceInputState =
                stateAndRefSourceWalletStateDetermined.getState().getData().getParticipants()
                        .stream().map(AbstractParty::getOwningKey)
                        .collect(Collectors.toList());
        List<PublicKey> listOfRequiredSignersInReceiverInputState =
                stateAndRefReceiverWalletStateDetermined.getState().getData().getParticipants()
                        .stream().map(AbstractParty::getOwningKey)
                        .collect(Collectors.toList());

        List<PublicKey> mergedListOfRequiredSigners = new ArrayList<>();
        mergedListOfRequiredSigners.addAll(listOfRequiredSignersInSourceInputState);
        mergedListOfRequiredSigners.addAll(listOfRequiredSignersInReceiverInputState);

        txBuilder.addCommand(new RDContract.Commands.wRDTransferCommand(), mergedListOfRequiredSigners);

        // 5. Verify the transaction based on wRD Contract verify method (in current / source party)
        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        // 6. Sign the transaction (in current / source party)
        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        // 7. Collect all the required signatures from other nodes
        // This CollectSignaturesFlow will trigger SignTransactionFlow in responder, that already called on step 2 above
        progressTracker.setCurrentStep(GATHERING_SIGS);
        List<FlowSession> sessions = new ArrayList<>();
        sessions.add(session);
        for (AbstractParty participant: stateAndRefSourceWalletStateDetermined.getState().getData().getParticipants()) {
            Party partyToInitiateFlow = (Party) participant;
            if (!partyToInitiateFlow.getOwningKey().equals(getOurIdentity().getOwningKey())
                    && !partyToInitiateFlow.getOwningKey().equals(receiverWholesaler.getOwningKey())) {
                FlowSession sessionOnlySign = initiateFlow(partyToInitiateFlow);
                // Simple approach to bypass blocking receive syncStatRequest in responder flow
                sessionOnlySign.send("SIGN_ONLY");
                sessions.add(sessionOnlySign);
            }
        }
        for (AbstractParty participant: stateAndRefReceiverWalletStateDetermined.getState().getData().getParticipants()) {
            Party partyToInitiateFlow = (Party) participant;
            if (!partyToInitiateFlow.getOwningKey().equals(getOurIdentity().getOwningKey())
                    && !partyToInitiateFlow.getOwningKey().equals(receiverWholesaler.getOwningKey())) {
                FlowSession sessionOnlySign = initiateFlow(partyToInitiateFlow);
                // Simple approach to bypass blocking receive syncStatRequest in responder flow
                sessionOnlySign.send("SIGN_ONLY");
                sessions.add(sessionOnlySign);
            }
        }
        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx,
                sessions, GATHERING_SIGS.childProgressTracker()));

        // 8. Return the output of the FinalityFlow which sends the transaction to the notary for verification and
        // the causes it to be persisted to the vault of appropriate nodes.
        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, sessions,
                FINALISING_TRANSACTION.childProgressTracker()));

        // 9. Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));

        return finalTx;
    }

    @NotNull
    private QueryCriteria getQueryCriteria() {
        List<UUID> walletIds = Arrays.asList(sourceWalletId.getId(), receiverWalletId.getId());
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

@InitiatedBy(wRDTransferFlow.class)
class wRDTransferFlowResponder extends FlowLogic<SignedTransaction> {
    private final FlowSession counterpartySession;
    private SecureHash txRespSignedId;

    private final ProgressTracker.Step RESP_GET_SIGN = new ProgressTracker.Step("Gathering responder sign tx.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return SignTransactionFlow.Companion.tracker();
        }
    };

    public wRDTransferFlowResponder(FlowSession counterpartySession) {
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