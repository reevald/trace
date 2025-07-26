package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.wRDAccountState;
import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;

import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.stream.Collectors;

@InitiatingFlow
@StartableByRPC
public class wRDCentralInitFlow extends FlowLogic<SignedTransaction> {
    
    private final Amount<Currency> initialAmount;
    
    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD Account.");
    private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
    private final ProgressTracker.Step GATHERING_SIGS = new ProgressTracker.Step("Gathering the counterparty's signature.") {
        @Override
        public ProgressTracker childProgressTracker() {
            return CollectSignaturesFlow.Companion.tracker();
        }
    };
    private final ProgressTracker.Step FINALISING_TRANSACTION = new ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
        @Override
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
        final Party notary = getServiceHub().getNetworkMapCache().getNotary(CordaX500Name.parse("O=Notary,L=Jakarta,C=ID"));
        final Party kdr = getOurIdentity();

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        
        final wRDAccountState outputState = new wRDAccountState(kdr, kdr, "IDR", initialAmount);

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addOutputState(outputState, wRDContract.ID)
                .addCommand(new wRDContract.Commands.wRDCentralInitCommand(), Arrays.asList(kdr.getOwningKey()));

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        List<Party> otherParties = outputState.getParticipants().stream()
                .map(el -> (Party) el)
                .distinct() // Add this line to remove duplicates
                .filter(party -> !party.equals(getOurIdentity())) // Use filter instead of remove for better stream integration
                .collect(Collectors.toList());

        List<FlowSession> sessions = otherParties.stream().map(el -> initiateFlow(el)).collect(Collectors.toList());

        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, sessions, GATHERING_SIGS.childProgressTracker()));

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
//        Note that in cases where states are being written to only a single party’s ledger and
//        there’s no counterparty, a notary does not need to be involved and the FinalityFlow
//        step can be skipped.
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, sessions, FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}

@InitiatedBy(wRDCentralInitFlow.class)
class wRDCentralInitFlowResponder extends FlowLogic<Void> {
    private final FlowSession counterpartySession;

    public wRDCentralInitFlowResponder(FlowSession counterpartySession) {
        this.counterpartySession = counterpartySession;
    }

    @Suspendable
    @Override
    public Void call() throws FlowException {
        SignedTransaction signedTransaction = subFlow(new SignTransactionFlow(counterpartySession) {
            @Suspendable
            @Override
            protected void checkTransaction(SignedTransaction stx) throws FlowException {
                
            }
        });
        subFlow(new ReceiveFinalityFlow(counterpartySession, signedTransaction.getId()));
        return null;
    }
}