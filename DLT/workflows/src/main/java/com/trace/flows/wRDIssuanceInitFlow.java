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
public class wRDIssuanceInitFlow extends FlowLogic<SignedTransaction> {
    
    private final Party wholesaler;
    private final Amount<Currency> amount;
    private final UniqueIdentifier sourceWalletId;
    
    private final ProgressTracker.Step GENERATING_TRANSACTION = new ProgressTracker.Step("Generating transaction based on new wRD Issuance Init.");
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

    public wRDIssuanceInitFlow(Party wholesaler, Amount<Currency> amount, UniqueIdentifier sourceWalletId) {
        this.wholesaler = wholesaler;
        this.amount = amount;
        this.sourceWalletId = sourceWalletId;
    }

    public wRDIssuanceInitFlow(Party wholesaler, Amount<Currency> amount) {
        this.wholesaler = wholesaler;
        this.amount = amount;
        this.sourceWalletId = null;
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
        
        QueryCriteria queryCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
        List<StateAndRef<wRDAccountState>> kdrStates;
        
        if (sourceWalletId != null) {
            List<java.util.UUID> walletIds = java.util.Collections.singletonList(sourceWalletId.getId());
            QueryCriteria linearIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, walletIds);
            QueryCriteria combinedCriteria = queryCriteria.and(linearIdCriteria);
            kdrStates = getServiceHub().getVaultService()
                    .queryBy(wRDAccountState.class, combinedCriteria).getStates()
                    .stream()
                    .filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(kdr) && 
                                         stateAndRef.getState().getData().getIssuer().equals(kdr))
                    .collect(Collectors.toList());
        } else {
            kdrStates = getServiceHub().getVaultService()
                    .queryBy(wRDAccountState.class, queryCriteria).getStates()
                    .stream()
                    .filter(stateAndRef -> stateAndRef.getState().getData().getOwner().equals(kdr) && 
                                         stateAndRef.getState().getData().getIssuer().equals(kdr))
                    .collect(Collectors.toList());
        }

        if (kdrStates.isEmpty()) {
            throw new FlowException("No KDR account state found" + (sourceWalletId != null ? " for walletId: " + sourceWalletId : ""));
        }

        StateAndRef<wRDAccountState> inputStateAndRef = kdrStates.get(0);
        wRDAccountState inputState = inputStateAndRef.getState().getData();

        if (inputState.getTokenBalance().getQuantity() < amount.getQuantity()) {
            throw new FlowException("Insufficient balance in KDR account");
        }

        Amount<Currency> remainingBalance = inputState.getTokenBalance().minus(amount);
        final wRDAccountState kdrOutputState = inputState.withNewBalance(remainingBalance);
        final wRDAccountState wholesalerOutputState = new wRDAccountState(wholesaler, kdr, "IDR", amount);

        final TransactionBuilder txBuilder = new TransactionBuilder(notary)
                .addInputState(inputStateAndRef)
                .addOutputState(kdrOutputState, wRDContract.ID)
                .addOutputState(wholesalerOutputState, wRDContract.ID)
                .addCommand(new wRDContract.Commands.wRDIssuanceInitCommand(), 
                           Arrays.asList(kdr.getOwningKey(), wholesaler.getOwningKey()));

        progressTracker.setCurrentStep(VERIFYING_TRANSACTION);
        txBuilder.verify(getServiceHub());

        progressTracker.setCurrentStep(SIGNING_TRANSACTION);
        final SignedTransaction partSignedTx = getServiceHub().signInitialTransaction(txBuilder);

        progressTracker.setCurrentStep(GATHERING_SIGS);
        FlowSession wholesalerSession = initiateFlow(wholesaler);
        SignedTransaction fullySignedTx = subFlow(new CollectSignaturesFlow(partSignedTx, 
                                                  Arrays.asList(wholesalerSession), 
                                                  GATHERING_SIGS.childProgressTracker()));

        progressTracker.setCurrentStep(FINALISING_TRANSACTION);
        SignedTransaction finalTx = subFlow(new FinalityFlow(fullySignedTx, Arrays.asList(wholesalerSession), 
                                       FINALISING_TRANSACTION.childProgressTracker()));
        
        // Report to Observer for AML monitoring
        subFlow(new ReportToObserverFlow(finalTx));
        
        return finalTx;
    }
}

@InitiatedBy(wRDIssuanceInitFlow.class)
class wRDIssuanceInitFlowResponder extends FlowLogic<Void> {
    private final FlowSession counterpartySession;

    public wRDIssuanceInitFlowResponder(FlowSession counterpartySession) {
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