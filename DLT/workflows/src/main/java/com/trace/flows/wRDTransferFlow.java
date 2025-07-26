package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.wRDAccountState;
import com.trace.contracts.wRDContract;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.flows.*;
import net.corda.core.identity.CordaX500Name;
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
public class wRDTransferFlow extends FlowLogic<SignedTransaction> {
    
    private final Party receiver;
    private final Amount<Currency> amount;
    private final UniqueIdentifier sourceWalletId;
    private final UniqueIdentifier targetWalletId;
    
    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on wRD Transfer.");
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

    public wRDTransferFlow(Party receiver, Amount<Currency> amount, UniqueIdentifier sourceWalletId, UniqueIdentifier targetWalletId) {
        this.receiver = receiver;
        this.amount = amount;
        this.sourceWalletId = sourceWalletId;
        this.targetWalletId = targetWalletId;
    }

    public wRDTransferFlow(Party receiver, Amount<Currency> amount) {
        this.receiver = receiver;
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
        final Party notary = getServiceHub().getNetworkMapCache().getNotary(CordaX500Name.parse("O=Notary,L=Jakarta,C=ID"));
        final Party sender = getOurIdentity();

        progressTracker.setCurrentStep(GENERATING_TRANSACTION);
        
        QueryCriteria queryCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        
        // Only query for sender states (which exist in sender's vault)
        List<StateAndRef<wRDAccountState>> senderStates;
        
        if (sourceWalletId != null) {
            List<java.util.UUID> walletIds = java.util.Collections.singletonList(sourceWalletId.getId());
            QueryCriteria linearIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
            QueryCriteria combinedCriteria = queryCriteria.and(linearIdCriteria);
            senderStates = getServiceHub().getVaultService()
                    .queryBy(wRDAccountState.class, combinedCriteria).getStates()
                    .stream()
                    .filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(sender))
                    .collect(Collectors.toList());
        } else {
            senderStates = getServiceHub().getVaultService()
                    .queryBy(wRDAccountState.class, queryCriteria).getStates()
                    .stream()
                    .filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(sender))
                    .collect(Collectors.toList());
        }

        if (senderStates.isEmpty()) {
            throw new FlowException("No sender account state found");
        }

        StateAndRef<wRDAccountState> senderStateAndRef = senderStates.get(0);
        wRDAccountState senderInputState = senderStateAndRef.getState().getData();

        if (senderInputState.getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance in sender account");
        }

        Amount<Currency> senderNewBalance = senderInputState.getTokenBalance().minus(amount);
        
        // Create updated sender state with reduced balance
        final wRDAccountState senderOutputState = senderInputState.withNewBalance(senderNewBalance);
        
        // Create new receiver state with the transferred amount
        // Use targetWalletId if provided, otherwise generate new one
        UniqueIdentifier receiverWalletId = targetWalletId != null ? targetWalletId : new UniqueIdentifier();
        
        final wRDAccountState receiverOutputState = new wRDAccountState(
                receiverWalletId,
                receiver,
                senderInputState.getIssuer(), // Same issuer as sender
                senderInputState.getTokenType(), // Same token type
                amount // The transferred amount
        );

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(senderStateAndRef)
                .addOutputState(senderOutputState, wRDContract.ID)
                .addOutputState(receiverOutputState, wRDContract.ID)
                .addCommand(new wRDContract.Commands.wRDTransferCommand(), 
                           Arrays.asList(sender.getOwningKey(), receiver.getOwningKey()));

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        FlowSession receiverSession = initiateFlow(receiver);
        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, 
                                                  Arrays.asList(receiverSession), 
                                                  GATHERING_SIGS.childProgressTracker()));

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(receiverSession), 
                                       FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}

@InitiatedBy(wRDTransferFlow.class)
class wRDTransferFlowResponder extends FlowLogic<Void> {
    private final FlowSession counterpartySession;

    public wRDTransferFlowResponder(FlowSession counterpartySession) {
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