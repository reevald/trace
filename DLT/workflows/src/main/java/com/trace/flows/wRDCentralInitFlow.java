package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.wRDAccountState;
import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.crypto.SecureHash;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;

@InitiatingFlow
@StartableByRPC
public class wRDCentralInitFlow extends FlowLogic<SignedTransaction> {

    private final Amount<Currency> initialAmount;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD Account.");
    private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
    private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.Companion.tracker();
        }
    };
    private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature" +
            " and recording transaction.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return FinalityFlow.Companion.tracker();
        }
    };
    // TODO: Add tracker for observer report flow, ensure enable companion tracker first.

    private final ProgressTracker progressTracker = new ProgressTracker(
            GENERATING_TRANSACTION,
            VERIFYING_TRANSACTION,
            SIGNING_TRANSACTION,
            GATHERING_SIGS,
            FINALISING_TRANSACTION
    );

    public wRDCentralInitFlow(Amount<Currency> initialAmount) {
        this.initialAmount = initialAmount;
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
        final Party kdr = getOurIdentity();

        // 2. Generating transaction blueprint
        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        // 2.1 Define output state, this flow only use one output state without input state
        final wRDAccountState outputState = new wRDAccountState(kdr, kdr, "IDR", initialAmount);
        final TransactionBuilder txBuilder;
        if (notary != null) {
            txBuilder = new TransactionBuilder(notary)
                    .addOutputState(outputState, wRDContract.ID)
                    .addCommand(new wRDContract.Commands.wRDCentralInitCommand(), Arrays.asList(kdr.getOwningKey()));
        } else {
            throw new IllegalArgumentException("Notary should be not null.");
        }

        // 3. Verify the transaction based on wRD Contract verify method (in current node)
        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        // 4. Sign the transaction (in current node)
        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        // 5. Collect all the required signatures from other nodes
        progressTracker.setCurrentStep(GATHERING_SIGS);
        List<FlowSession> sessions = new ArrayList<>();
        // Since outputState.getParticipants() for this flow consist of kdr node only and already signed before
        // then no need to re-sign, and we can skip this step safely.

        // 6. Return the output of the FinalityFlow which sends the transaction to the notary for verification and
        // the causes it to be persisted to the vault of appropriate nodes.
        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        // Caveat: Note that in cases where states are being written to only a single party’s ledger and there’s no
        // counterparty, a notary does not need to be involved and the FinalityFlow step can be skipped.
        // However, calling FinalityFlow when there’s only one party involved in a state will result in a log message.
         SignedTransaction finalTx = subFlow(new FinalityFlow(partSignedTx, sessions,
                FINALISING_TRANSACTION.childProgressTracker()));

        // 7. Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));

        return finalTx;
    }
}

// Note: This flow responder prepared but only used when there are multiple KDR nodes later.
// Currently with single KDR node, it will be ignored.
@InitiatedBy(wRDCentralInitFlow.class)
class wRDCentralInitFlowResponder extends FlowLogic<SignedTransaction> {
    private final FlowSession counterpartySession;
    private SecureHash txRespSignedId;

    private final ProgressTracker.Step RESP_GET_SIGN = new ProgressTracker.Step("Gathering responder sign tx.") {
        @Override
        @NotNull
        public ProgressTracker childProgressTracker() {
            return SignTransactionFlow.Companion.tracker();
        }
    };

    public wRDCentralInitFlowResponder(FlowSession counterpartySession) {
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