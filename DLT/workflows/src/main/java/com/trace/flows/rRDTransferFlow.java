package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.rRDAccountState;
import com.trace.contracts.rRDContract;
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
import java.util.stream.Collectors;

@InitiatingFlow
@StartableByRPC
public class rRDTransferFlow extends FlowLogic<SignedTransaction> {

    private final String receiverOwner;
    private final Amount<Currency> amount;
    private final UniqueIdentifier sourceWalletId;
    private final UniqueIdentifier targetWalletId;

    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new rRD transfer.");
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

    public rRDTransferFlow(String receiverOwner, Amount<Currency> amount, UniqueIdentifier sourceWalletId, UniqueIdentifier targetWalletId) {
        this.receiverOwner = receiverOwner;
        this.amount = amount;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
    }

    public rRDTransferFlow(String receiverOwner, Amount<Currency> amount) {
        this.receiverOwner = receiverOwner;
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
        final Party observer = getServiceHub().getNetworkMapCache().getPeerByLegalName(net.corda.core.identity.CordaX500Name.parse("O=Observer,L=Jakarta,C=ID"));
        final Party wholesaler = getOurIdentity();

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);

        QueryCriteria generalCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        
        // Find sender states (only in current node's vault)
        List<StateAndRef<rRDAccountState>> senderStates;
        
        if (sourceWalletId != null) {
            List<java.util.UUID> walletIds = java.util.Collections.singletonList(sourceWalletId.getId());
            QueryCriteria linearIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
            QueryCriteria combinedCriteria = generalCriteria.and(linearIdCriteria);
            senderStates = getServiceHub().getVaultService()
                    .queryBy(rRDAccountState.class, combinedCriteria).getStates()
                    .stream()
                    .filter(stateAndRef -> stateAndRef.getState().getData().getWholesalerParty().equals(wholesaler))
                    .collect(Collectors.toList());
        } else {
            // Find any rRD state owned by this wholesaler that has sufficient balance
            senderStates = getServiceHub().getVaultService()
                    .queryBy(rRDAccountState.class, generalCriteria).getStates()
                    .stream()
                    .filter(stateAndRef -> {
                        rRDAccountState state = stateAndRef.getState().getData();
                        return state.getWholesalerParty().equals(wholesaler) && 
                               state.getTokenBalance().getQuantity() >= amount.getQuantity();
                    })
                    .collect(Collectors.toList());
        }

        if (senderStates.isEmpty()) {
            throw new FlowException("No suitable sender rRD account state found with sufficient balance");
        }

        StateAndRef<rRDAccountState> senderStateAndRef = senderStates.get(0);
        rRDAccountState senderInputState = senderStateAndRef.getState().getData();

        if (senderInputState.getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance in sender rRD account");
        }

        // Create updated sender state with reduced balance
        rRDAccountState senderOutputState = senderInputState.withNewBalance(
                senderInputState.getTokenBalance().minus(amount)
        );

        // Create new receiver state with the transferred amount
        // Use targetWalletId if provided, otherwise generate new one
        UniqueIdentifier receiverWalletId = targetWalletId != null ? targetWalletId : new UniqueIdentifier();
        
        rRDAccountState receiverOutputState = new rRDAccountState(
                receiverWalletId,
                receiverOwner,
                senderInputState.getIssuer(), // Same issuer as sender
                senderInputState.getTokenType(), // Same token type
                amount, // The transferred amount
                wholesaler // Same wholesaler party
        );

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(senderStateAndRef)
                .addOutputState(senderOutputState, rRDContract.ID)
                .addOutputState(receiverOutputState, rRDContract.ID)
                .addCommand(new rRDContract.Commands.rRDTransferCommand(), wholesaler.getOwningKey());

        if (observer != null) {
            txBuilder.addCommand(new rRDContract.Commands.rRDTransferCommand(), observer.getOwningKey());
        }

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        // For rRD transfers, we typically don't need additional signatures since it's within the same wholesaler
        // But if observer is involved, we might need their signature
        SignedTransaction fullySignedTx = partSignedTx;
        
        if (observer != null) {
            FlowSession observerSession = initiateFlow(observer);
            fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, 
                                                             Arrays.asList(observerSession), 
                                                             GATHERING_SIGS.childProgressTracker()));
        }

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        List<FlowSession> sessions = observer != null ? Arrays.asList(initiateFlow(observer)) : Arrays.asList();
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, sessions, 
                                       FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}

@InitiatedBy(rRDTransferFlow.class)
class rRDTransferFlowResponder extends FlowLogic<SignedTransaction> {

    private final FlowSession counterpartySession;

    public rRDTransferFlowResponder(FlowSession counterpartySession) {
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