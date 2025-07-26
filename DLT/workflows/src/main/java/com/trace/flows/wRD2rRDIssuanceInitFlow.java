package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.rRDAccountState;
import com.trace.contracts.rRDContract;
import com.trace.states.wRDAccountState;
import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
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
import java.util.UUID;

@InitiatingFlow
@StartableByRPC
public class wRD2rRDIssuanceInitFlow extends FlowLogic<SignedTransaction> {

    private final Amount<Currency> amount;
    private final UniqueIdentifier sourceWalletId;
    private final UniqueIdentifier targetWalletId;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD to rRD issuance init.");
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

    public wRD2rRDIssuanceInitFlow(Amount<Currency> amount, UniqueIdentifier sourceWalletId, UniqueIdentifier targetWalletId) {
        this.amount = amount;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
    }

    public wRD2rRDIssuanceInitFlow(Amount<Currency> amount, String peritelId) {
        this.amount = amount;
        this.sourceWalletId = null;
        this.targetWalletId = new UniqueIdentifier();
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
        List<StateAndRef<wRDAccountState>> wholesalerStates;
        
        if (sourceWalletId != null) {
            List<java.util.UUID> walletIds = java.util.Collections.singletonList(sourceWalletId.getId());
            QueryCriteria linearIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
            QueryCriteria combinedCriteria = generalCriteria.and(linearIdCriteria);
            wholesalerStates = getServiceHub().getVaultService().queryBy(wRDAccountState.class, combinedCriteria).getStates()
                    .stream().filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(getOurIdentity())).collect(java.util.stream.Collectors.toList());
        } else {
            wholesalerStates = getServiceHub().getVaultService().queryBy(wRDAccountState.class, generalCriteria).getStates()
                    .stream().filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(getOurIdentity())).collect(java.util.stream.Collectors.toList());
        }

        if (wholesalerStates.isEmpty()) {
            throw new FlowException("Wholesaler wRD account state not found" + (sourceWalletId != null ? " for walletId: " + sourceWalletId : ""));
        }

        StateAndRef<wRDAccountState> wholesalerInputState = wholesalerStates.get(0);

        wRDAccountState wholesalerOutputState = new wRDAccountState(
                wholesalerInputState.getState().getData().getWalletId(),
                wholesalerInputState.getState().getData().getOwner(),
                wholesalerInputState.getState().getData().getIssuer(),
                wholesalerInputState.getState().getData().getTokenType(),
                wholesalerInputState.getState().getData().getTokenBalance().minus(amount)
        );

        String peritelOwner = getOurIdentity().getName().getOrganisation() + "-P-" + targetWalletId.toString().substring(0, 8);
        rRDAccountState peritelOutputState = new rRDAccountState(
                targetWalletId,
                peritelOwner,
                peritelOwner,
                "IDR",
                amount,
                getOurIdentity()
        );

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(wholesalerInputState)
                .addOutputState(wholesalerOutputState, wRDContract.ID)
                .addOutputState(peritelOutputState, rRDContract.ID)
                .addCommand(new rRDContract.Commands.wRD2rRDIssuanceInitCommand(), getOurIdentity().getOwningKey());

        if (observer != null) {
            txBuilder.addCommand(new rRDContract.Commands.wRD2rRDIssuanceInitCommand(), observer.getOwningKey());
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