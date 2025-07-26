package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.node.StatesToRecord;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.ProgressTracker;

/**
 * Report To Observer Flow
 * This flow is called by all other flows after FinalityFlow to report completed transactions
 * to the Observer node for AML monitoring and compliance purposes.
 * 
 * The Observer node uses StatesToRecord.ALL_VISIBLE to automatically see all transactions.
 * This flow simply notifies the Observer that a transaction has been completed.
 */
@InitiatingFlow
@StartableByRPC
public class ReportToObserverFlow extends FlowLogic<Void> {
    
    private final SignedTransaction transaction;

    public ReportToObserverFlow(SignedTransaction transaction) {
        this.transaction = transaction;
    }

    @Suspendable
    @Override
    public Void call() throws FlowException {
        try {
            // Get Observer party
            Party observer = getServiceHub().getNetworkMapCache().getPeerByLegalName(
                net.corda.core.identity.CordaX500Name.parse("O=Observer,L=Jakarta,C=ID"));
            
            if (observer != null) {
                // Initiate session with Observer and send the transaction
                FlowSession observerSession = initiateFlow(observer);
                subFlow(new SendTransactionFlow(observerSession, transaction));
                
                getLogger().info("Transaction reported to Observer: " + transaction.getId());
            } else {
                getLogger().warn("Observer node not found in network map");
            }
            
        } catch (Exception e) {
            // Don't fail the main transaction if Observer reporting fails
            getLogger().warn("Failed to report to Observer: " + e.getMessage());
        }
        
        return null;
    }
}

/**
 * Report To Observer Flow Responder
 * This flow runs on the Observer node to receive transaction reports
 * Uses ReceiveTransactionFlow with StatesToRecord.ALL_VISIBLE
 */
@InitiatedBy(ReportToObserverFlow.class)
class ReportToObserverFlowResponder extends FlowLogic<Void> {
    
    private final FlowSession counterpartySession;

    public ReportToObserverFlowResponder(FlowSession counterpartySession) {
        this.counterpartySession = counterpartySession;
    }

    @Suspendable
    @Override
    public Void call() throws FlowException {
        // Verify this is running on Observer node
        if (!getOurIdentity().getName().getOrganisation().equals("Observer")) {
            getLogger().warn("ReportToObserverFlowResponder should only run on Observer node");
            return null;
        }

        try {
            // Receive the transaction with ALL_VISIBLE recording
            subFlow(new ReceiveTransactionFlow(counterpartySession, true, StatesToRecord.ALL_VISIBLE));
            
            getLogger().info("Transaction received and recorded by Observer with ALL_VISIBLE");
            
        } catch (Exception e) {
            getLogger().error("Failed to receive transaction: " + e.getMessage(), e);
        }
        
        return null;
    }
}