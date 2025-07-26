package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
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

@InitiatingFlow
@StartableByRPC
public class wRDRedemptionFlow extends FlowLogic<SignedTransaction> {

    private final Party kdr;
    private final Amount<Currency> amount;
    private final UniqueIdentifier sourceWalletId;
    private final UniqueIdentifier targetWalletId;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD redemption.");
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

    public wRDRedemptionFlow(Party kdr, Amount<Currency> amount, UniqueIdentifier sourceWalletId, UniqueIdentifier targetWalletId) {
        this.kdr = kdr;
        this.amount = amount;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
    }

    public wRDRedemptionFlow(Party kdr, Amount<Currency> amount) {
        this.kdr = kdr;
        this.amount = amount;
        this.sourceWalletId = null;
        this.targetWalletId = null;
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
        
        // Only query for wholesaler states (which exist in wholesaler's vault)
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
            throw new FlowException("No wholesaler account state found");
        }

        StateAndRef<wRDAccountState> wholesalerStateAndRef = wholesalerStates.get(0);
        wRDAccountState wholesalerInputState = wholesalerStateAndRef.getState().getData();

        if (wholesalerInputState.getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance in wholesaler account");
        }

        // Create updated wholesaler state with reduced balance
        wRDAccountState wholesalerOutputState = wholesalerInputState.withNewBalance(
                wholesalerInputState.getTokenBalance().minus(amount)
        );

        // Create new KDR state with the redeemed amount
        // Use targetWalletId if provided, otherwise generate new one
        UniqueIdentifier kdrWalletId = targetWalletId != null ? targetWalletId : new UniqueIdentifier();
        
        wRDAccountState kdrOutputState = new wRDAccountState(
                kdrWalletId,
                kdr,
                wholesalerInputState.getIssuer(), // Same issuer as wholesaler
                wholesalerInputState.getTokenType(), // Same token type
                amount // The redeemed amount
        );

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(wholesalerStateAndRef)
                .addOutputState(wholesalerOutputState, wRDContract.ID)
                .addOutputState(kdrOutputState, wRDContract.ID)
                .addCommand(new wRDContract.Commands.wRDRedemptionCommand(), Arrays.asList(getOurIdentity().getOwningKey(), kdr.getOwningKey()));

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        FlowSession kdrSession = initiateFlow(kdr);
        final SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, Arrays.asList(kdrSession), CollectSignaturesFlow.Companion.tracker()));

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(kdrSession), FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}

@InitiatedBy(wRDRedemptionFlow.class)
class wRDRedemptionFlowResponder extends FlowLogic<SignedTransaction> {

    private final FlowSession counterpartySession;

    public wRDRedemptionFlowResponder(FlowSession counterpartySession) {
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