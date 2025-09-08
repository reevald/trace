package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.contracts.RDContract;
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
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

@InitiatingFlow
@StartableByRPC
public class wRDIssuanceInitFlow extends FlowLogic<SignedTransaction> {

    private final Party wholesaler;
    private final Amount<Currency> amount;
    private final UniqueIdentifier sourceWalletId;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD Issuance Init.");
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

    public wRDIssuanceInitFlow(Party wholesaler, Amount<Currency> amount, UniqueIdentifier sourceWalletId) {
        this.wholesaler = wholesaler;
        this.amount = amount;
        this.sourceWalletId = sourceWalletId;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
        // 1. Define all parties involved
        final Party notary = getServiceHub().getNetworkMapCache().getNotary(CordaX500Name.parse("O=Notary,L=Jakarta," +
                "C=ID"));
        if (notary == null) {
            throw new IllegalArgumentException("Notary should be not null.");
        }
        final Party kdr = getOurIdentity();

        // 2. Generating transaction blueprint
        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        // 2.1 Prepare input state
        QueryCriteria queryCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        List<java.util.UUID> walletIds = java.util.Collections.singletonList(sourceWalletId.getId());
        QueryCriteria linearIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
        QueryCriteria combinedCriteria = queryCriteria.and(linearIdCriteria);

        List<StateAndRef<wRDAccountState>> kdrStates = getServiceHub().getVaultService()
                .queryBy(wRDAccountState.class, combinedCriteria).getStates()
                .stream()
                // The issuer not always KDR, for example once successful complete issuance to wholesaler A, the
                // issuer will equal A.
                .filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(kdr))
                .collect(Collectors.toList());

        if (kdrStates.isEmpty()) {
            throw new FlowException("No KDR account state found for walletId: " + sourceWalletId);
        }

        StateAndRef<wRDAccountState> inputStateAndRef = kdrStates.get(0);
        wRDAccountState inputState = inputStateAndRef.getState().getData();

        // 2.2 Pre-verify amount token
        if (inputState.getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance in KDR account");
        }

        // 2.3 Prepare output states
        Amount<Currency> remainingBalance = inputState.getTokenBalance().minus(amount);
        final wRDAccountState kdrOutputState = inputState.withNewBalanceAndIssuer(remainingBalance, wholesaler);
        final wRDAccountState wholesalerOutputState = new wRDAccountState(wholesaler, kdr, "IDR", amount);

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        List<PublicKey> listOfRequiredSigners = inputState.getParticipants()
                .stream().map(AbstractParty::getOwningKey)
                .collect(Collectors.toList());

        listOfRequiredSigners.add(wholesaler.getOwningKey());

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(inputStateAndRef)
                .addOutputState(kdrOutputState, RDContract.ID)
                .addOutputState(wholesalerOutputState, RDContract.ID)
                .addCommand(new RDContract.Commands.wRDIssuanceInitCommand(), listOfRequiredSigners);

        // 3. Verify the transaction based on wRD Contract verify method (in current node)
        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        // 4. Sign the transaction (in current node)
        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        // 5. Collect all the required signatures from other nodes
        progressTracker.setCurrentStep(GATHERING_SIGS);
        List<FlowSession> sessions = new ArrayList<>();
        for (AbstractParty participant: inputState.getParticipants()) {
            Party partyToInitiateFlow = (Party) participant;
            if (!partyToInitiateFlow.getOwningKey().equals(getOurIdentity().getOwningKey())) {
                sessions.add(initiateFlow(partyToInitiateFlow));
            }
        }
        sessions.add(initiateFlow(wholesaler));
        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx,
                sessions, GATHERING_SIGS.childProgressTracker()));

        // 6. Return the output of the FinalityFlow which sends the transaction to the notary for verification and
        // the causes it to be persisted to the vault of appropriate nodes.
        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, sessions,
                FINALISING_TRANSACTION.childProgressTracker()));

        // 7. Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));

        return finalTx;
    }
}

@InitiatedBy(wRDIssuanceInitFlow.class)
class wRDIssuanceInitFlowResponder extends FlowLogic<SignedTransaction> {
    private final FlowSession counterpartySession;
    private SecureHash txRespSignedId;

    private final ProgressTracker.Step RESP_GET_SIGN = new ProgressTracker.Step("Gathering responder sign tx.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return SignTransactionFlow.Companion.tracker();
        }
    };

    public wRDIssuanceInitFlowResponder(FlowSession counterpartySession) {
        this.counterpartySession = counterpartySession;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
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

        // Create a sign transaction flow
        SignTxFlow signTxFlow = new SignTxFlow(counterpartySession, RESP_GET_SIGN.childProgressTracker());

        // Run the sign transaction flows to sign the transaction
        subFlow(signTxFlow);

        // Run the ReceiveFinalityFlow to finalize the transaction and persist it to the vault.
        return subFlow(new ReceiveFinalityFlow(counterpartySession, txRespSignedId));
    }
}