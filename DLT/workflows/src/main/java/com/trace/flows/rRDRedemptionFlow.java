package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.rRDAccountState;
import com.trace.contracts.rRDContract;
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
public class rRDRedemptionFlow extends FlowLogic<SignedTransaction> {

    private final Amount<Currency> amount;
    private final String retailOwner;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new rRD redemption.");
    private final ProgressTracker.Step VERIFYING_TRANSACTION = new ProgressTracker.Step("Verifying contract constraints.");
    private final ProgressTracker.Step SIGNING_TRANSACTION = new ProgressTracker.Step("Signing transaction with our private key.");
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
            FINALISING_TRANSACTION
    );

    public rRDRedemptionFlow(Amount<Currency> amount, String retailOwner) {
        this.amount = amount;
        this.retailOwner = retailOwner;
    }

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public SignedTransaction call() throws FlowException {
        final Party notary = getServiceHub().getNetworkMapCache().getNotaryIdentities().get(0);
        final Party observer = getServiceHub().getNetworkMapCache().getPeerByLegalName(net.corda.core.identity.CordaX500Name.parse("O=Observer,L=Jakarta,C=ID"));

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);

        QueryCriteria generalCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        List<StateAndRef<rRDAccountState>> retailStates = getServiceHub().getVaultService().queryBy(rRDAccountState.class, generalCriteria).getStates()
                .stream().filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(retailOwner)).collect(java.util.stream.Collectors.toList());
        List<StateAndRef<rRDAccountState>> peritelStates = getServiceHub().getVaultService().queryBy(rRDAccountState.class, generalCriteria).getStates()
                .stream().filter(stateAndRef -> stateAndRef.getState().getData().isPeritel()).collect(java.util.stream.Collectors.toList());

        if (retailStates.isEmpty() || peritelStates.isEmpty()) {
            throw new FlowException("Required states not found");
        }

        StateAndRef<rRDAccountState> retailInputState = retailStates.get(0);
        StateAndRef<rRDAccountState> peritelInputState = peritelStates.get(0);

        rRDAccountState retailOutputState = new rRDAccountState(
                retailInputState.getState().getData().getWalletId(),
                retailInputState.getState().getData().getOwner(),
                retailInputState.getState().getData().getIssuer(),
                retailInputState.getState().getData().getTokenType(),
                retailInputState.getState().getData().getTokenBalance().minus(amount),
                retailInputState.getState().getData().getWholesalerParty()
        );

        rRDAccountState peritelOutputState = new rRDAccountState(
                peritelInputState.getState().getData().getWalletId(),
                peritelInputState.getState().getData().getOwner(),
                peritelInputState.getState().getData().getIssuer(),
                peritelInputState.getState().getData().getTokenType(),
                peritelInputState.getState().getData().getTokenBalance().plus(amount),
                peritelInputState.getState().getData().getWholesalerParty()
        );

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(retailInputState)
                .addInputState(peritelInputState)
                .addOutputState(retailOutputState, rRDContract.ID)
                .addOutputState(peritelOutputState, rRDContract.ID)
                .addCommand(new rRDContract.Commands.rRDRedemptionCommand(), getOurIdentity().getOwningKey());

        if (observer != null) {
            txBuilder.addCommand(new rRDContract.Commands.rRDRedemptionCommand(), observer.getOwningKey());
        }

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction signedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(signedTx, Arrays.asList(), FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}