package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.wRDAccountState;
import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.transactions.TransactionBuilder;
import net.corda.core.utilities.ProgressTracker;

import java.util.Arrays;
import java.util.Currency;
import java.util.List;

@InitiatingFlow
@StartableByRPC
public class wRDIssuanceFlow extends FlowLogic<SignedTransaction> {

    private final Party receiver;
    private final Amount<Currency> amount;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD issuance.");
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

    public wRDIssuanceFlow(Party receiver, Amount<Currency> amount) {
        this.receiver = receiver;
        this.amount = amount;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
        final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);

        QueryCriteria generalCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        List<StateAndRef<wRDAccountState>> kdrStates = getServiceHub().getVaultService().queryBy(wRDAccountState.class, generalCriteria).getStates()
                .stream().filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(getOurIdentity())).collect(java.util.stream.Collectors.toList());
        List<StateAndRef<wRDAccountState>> receiverStates = getServiceHub().getVaultService().queryBy(wRDAccountState.class, generalCriteria).getStates()
                .stream().filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(receiver)).collect(java.util.stream.Collectors.toList());

        if (kdrStates.isEmpty() || receiverStates.isEmpty()) {
            throw new FlowException("Required states not found");
        }

        StateAndRef<wRDAccountState> kdrInputState = kdrStates.get(0);
        StateAndRef<wRDAccountState> receiverInputState = receiverStates.get(0);

        wRDAccountState kdrOutputState = new wRDAccountState(
                kdrInputState.getState().getData().getWalletId(),
                kdrInputState.getState().getData().getOwner(),
                kdrInputState.getState().getData().getIssuer(),
                kdrInputState.getState().getData().getTokenType(),
                kdrInputState.getState().getData().getTokenBalance().minus(amount)
        );

        wRDAccountState receiverOutputState = new wRDAccountState(
                receiverInputState.getState().getData().getWalletId(),
                receiverInputState.getState().getData().getOwner(),
                receiverInputState.getState().getData().getIssuer(),
                receiverInputState.getState().getData().getTokenType(),
                receiverInputState.getState().getData().getTokenBalance().plus(amount)
        );

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(kdrInputState)
                .addInputState(receiverInputState)
                .addOutputState(kdrOutputState, wRDContract.ID)
                .addOutputState(receiverOutputState, wRDContract.ID)
                .addCommand(new wRDContract.Commands.wRDIssuanceCommand(), Arrays.asList(getOurIdentity().getOwningKey(), receiver.getOwningKey()));

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        FlowSession receiverSession = initiateFlow(receiver);
        final SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, Arrays.asList(receiverSession), CollectSignaturesFlow.Companion.tracker()));

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(receiverSession), FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}

@InitiatedBy(wRDIssuanceFlow.class)
class wRDIssuanceFlowResponder extends FlowLogic<SignedTransaction> {

    private final FlowSession counterpartySession;

    public wRDIssuanceFlowResponder(FlowSession counterpartySession) {
        this.counterpartySession = counterpartySession;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
        return subFlow(new SignTransactionFlow(counterpartySession) {
            @Override
            protected void checkTransaction(SignedTransaction stx) {
            }
        });
    }
}