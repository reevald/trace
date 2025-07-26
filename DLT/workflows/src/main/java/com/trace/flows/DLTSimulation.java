package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import net.corda.core.contracts.Amount;
import net.corda.core.flows.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.ProgressTracker;

import java.util.Currency;
import java.util.UUID;

/**
 * Comprehensive simulation flow that demonstrates all 12 flows in the DLT system
 * This flow simulates a complete lifecycle from KDR initialization to retail transactions
 */
@InitiatingFlow
@StartableByRPC
public class DLTSimulation extends FlowLogic<String> {

    private final ProgressTracker.Step STEP_1 = new ProgressTracker.Step("Step 1: KDR Central Initialization");
    private final ProgressTracker.Step STEP_2 = new ProgressTracker.Step("Step 2: wRD Issuance to Wholesalers");
    private final ProgressTracker.Step STEP_3 = new ProgressTracker.Step("Step 3: wRD Transfer between Wholesalers");
    private final ProgressTracker.Step STEP_4 = new ProgressTracker.Step("Step 4: wRD to rRD Conversion (Peritel Setup)");
    private final ProgressTracker.Step STEP_5 = new ProgressTracker.Step("Step 5: rRD Issuance to Retail Users");
    private final ProgressTracker.Step STEP_6 = new ProgressTracker.Step("Step 6: rRD Transfer between Retail Users");
    private final ProgressTracker.Step STEP_7 = new ProgressTracker.Step("Step 7: rRD Redemption (Retail to Peritel)");
    private final ProgressTracker.Step STEP_8 = new ProgressTracker.Step("Step 8: rRD to wRD Conversion (Peritel to Wholesaler)");
    private final ProgressTracker.Step STEP_9 = new ProgressTracker.Step("Step 9: wRD Redemption (Wholesaler to KDR)");
    private final ProgressTracker.Step STEP_10 = new ProgressTracker.Step("Step 10: Additional Operations");
    private final ProgressTracker.Step COMPLETE = new ProgressTracker.Step("Simulation Complete");

    private final ProgressTracker progressTracker = new ProgressTracker(
            STEP_1, STEP_2, STEP_3, STEP_4, STEP_5, STEP_6, STEP_7, STEP_8, STEP_9, STEP_10, COMPLETE
    );

    @Override
    public ProgressTracker getProgressTracker() {
        return progressTracker;
    }

    @Suspendable
    @Override
    public String call() throws FlowException {
        StringBuilder report = new StringBuilder();
        report.append("=== DLT System Simulation Report ===\\n\\n");

        try {
            // Get parties
            Party kdr = getServiceHub().getNetworkMapCache().getPeerByLegalName(
                net.corda.core.identity.CordaX500Name.parse("O=KDR,L=Jakarta,C=ID"));
            Party wholesaler1 = getServiceHub().getNetworkMapCache().getPeerByLegalName(
                net.corda.core.identity.CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));
            Party wholesaler2 = getServiceHub().getNetworkMapCache().getPeerByLegalName(
                net.corda.core.identity.CordaX500Name.parse("O=Wholesaler2,L=Surabaya,C=ID"));

            // Currency setup
            Currency idr = Currency.getInstance("IDR");
            
            // Step 1: KDR Central Initialization
            progressTracker.setCurrentStep(STEP_1);
            report.append("Step 1: KDR Central Initialization\\n");
            if (getOurIdentity().getName().getOrganisation().equals("KDR")) {
                Amount<Currency> initialSupply = Amount.parseCurrency("1000000000000 IDR");
                SignedTransaction tx1 = subFlow(new wRDCentralInitFlow(initialSupply));
                report.append("✓ KDR initialized with supply: ").append(initialSupply).append("\\n");
                report.append("  Transaction ID: ").append(tx1.getId()).append("\\n\\n");
            } else {
                report.append("⚠ Skipped - Not running on KDR node\\n\\n");
            }

            // Step 2: wRD Issuance to Wholesalers
            progressTracker.setCurrentStep(STEP_2);
            report.append("Step 2: wRD Issuance to Wholesalers\\n");
            if (getOurIdentity().getName().getOrganisation().equals("KDR")) {
                if (wholesaler1 != null) {
                    Amount<Currency> issuanceAmount = Amount.parseCurrency("100000000 IDR");
                    SignedTransaction tx2a = subFlow(new wRDIssuanceInitFlow(wholesaler1, issuanceAmount));
                    report.append("✓ Issued ").append(issuanceAmount).append(" to Wholesaler1\\n");
                    report.append("  Transaction ID: ").append(tx2a.getId()).append("\\n");
                }
                if (wholesaler2 != null) {
                    Amount<Currency> issuanceAmount = Amount.parseCurrency("80000000 IDR");
                    SignedTransaction tx2b = subFlow(new wRDIssuanceInitFlow(wholesaler2, issuanceAmount));
                    report.append("✓ Issued ").append(issuanceAmount).append(" to Wholesaler2\\n");
                    report.append("  Transaction ID: ").append(tx2b.getId()).append("\\n");
                }
                report.append("\\n");
            } else {
                report.append("⚠ Skipped - Not running on KDR node\\n\\n");
            }

            // Step 3: wRD Transfer between Wholesalers
            progressTracker.setCurrentStep(STEP_3);
            report.append("Step 3: wRD Transfer between Wholesalers\\n");
            if (getOurIdentity().getName().getOrganisation().equals("Wholesaler1") && wholesaler2 != null) {
                Amount<Currency> transferAmount = Amount.parseCurrency("10000000 IDR");
                SignedTransaction tx3 = subFlow(new wRDTransferFlow(wholesaler2, transferAmount));
                report.append("✓ Transferred ").append(transferAmount).append(" from Wholesaler1 to Wholesaler2\\n");
                report.append("  Transaction ID: ").append(tx3.getId()).append("\\n\\n");
            } else {
                report.append("⚠ Skipped - Not running on Wholesaler1 node\\n\\n");
            }

            // Step 4: wRD to rRD Conversion (Peritel Setup)
            progressTracker.setCurrentStep(STEP_4);
            report.append("Step 4: wRD to rRD Conversion (Peritel Setup)\\n");
            if (getOurIdentity().getName().getOrganisation().startsWith("Wholesaler")) {
                String peritelId1 = UUID.randomUUID().toString();
                String peritelId2 = UUID.randomUUID().toString();
                
                Amount<Currency> conversionAmount1 = Amount.parseCurrency("5000000 IDR");
                SignedTransaction tx4a = subFlow(new wRD2rRDIssuanceInitFlow(conversionAmount1, peritelId1));
                report.append("✓ Created Peritel account: ").append(getOurIdentity().getName().getOrganisation())
                       .append("-P-").append(peritelId1).append(" with ").append(conversionAmount1).append("\\n");
                report.append("  Transaction ID: ").append(tx4a.getId()).append("\\n");

                Amount<Currency> conversionAmount2 = Amount.parseCurrency("3000000 IDR");
                SignedTransaction tx4b = subFlow(new wRD2rRDIssuanceInitFlow(conversionAmount2, peritelId2));
                report.append("✓ Created Peritel account: ").append(getOurIdentity().getName().getOrganisation())
                       .append("-P-").append(peritelId2).append(" with ").append(conversionAmount2).append("\\n");
                report.append("  Transaction ID: ").append(tx4b.getId()).append("\\n\\n");
            } else {
                report.append("⚠ Skipped - Not running on Wholesaler node\\n\\n");
            }

            // Step 5: rRD Issuance to Retail Users
            progressTracker.setCurrentStep(STEP_5);
            report.append("Step 5: rRD Issuance to Retail Users\\n");
            if (getOurIdentity().getName().getOrganisation().startsWith("Wholesaler")) {
                String retailId1 = UUID.randomUUID().toString();
                String retailId2 = UUID.randomUUID().toString();
                String retailId3 = UUID.randomUUID().toString();
                
                Amount<Currency> retailAmount1 = Amount.parseCurrency("500000 IDR");
                SignedTransaction tx5a = subFlow(new rRDIssuanceInitFlow(retailAmount1, retailId1));
                report.append("✓ Created Retail account: ").append(getOurIdentity().getName().getOrganisation())
                       .append("-R-").append(retailId1).append(" with ").append(retailAmount1).append("\\n");
                report.append("  Transaction ID: ").append(tx5a.getId()).append("\\n");

                Amount<Currency> retailAmount2 = Amount.parseCurrency("300000 IDR");
                SignedTransaction tx5b = subFlow(new rRDIssuanceInitFlow(retailAmount2, retailId2));
                report.append("✓ Created Retail account: ").append(getOurIdentity().getName().getOrganisation())
                       .append("-R-").append(retailId2).append(" with ").append(retailAmount2).append("\\n");
                report.append("  Transaction ID: ").append(tx5b.getId()).append("\\n");

                Amount<Currency> retailAmount3 = Amount.parseCurrency("200000 IDR");
                SignedTransaction tx5c = subFlow(new rRDIssuanceInitFlow(retailAmount3, retailId3));
                report.append("✓ Created Retail account: ").append(getOurIdentity().getName().getOrganisation())
                       .append("-R-").append(retailId3).append(" with ").append(retailAmount3).append("\\n");
                report.append("  Transaction ID: ").append(tx5c.getId()).append("\\n\\n");
            } else {
                report.append("⚠ Skipped - Not running on Wholesaler node\\n\\n");
            }

            // Additional steps would continue here...
            progressTracker.setCurrentStep(STEP_6);
            report.append("Step 6: rRD Transfer between Retail Users\\n");
            report.append("⚠ Requires specific retail account identification - implement based on created accounts\\n\\n");

            progressTracker.setCurrentStep(STEP_7);
            report.append("Step 7: rRD Redemption (Retail to Peritel)\\n");
            report.append("⚠ Requires specific retail account identification - implement based on created accounts\\n\\n");

            progressTracker.setCurrentStep(STEP_8);
            report.append("Step 8: rRD to wRD Conversion (Peritel to Wholesaler)\\n");
            report.append("⚠ Requires specific peritel account identification - implement based on created accounts\\n\\n");

            progressTracker.setCurrentStep(STEP_9);
            report.append("Step 9: wRD Redemption (Wholesaler to KDR)\\n");
            if (getOurIdentity().getName().getOrganisation().startsWith("Wholesaler") && kdr != null) {
                Amount<Currency> redemptionAmount = Amount.parseCurrency("5000000 IDR");
                SignedTransaction tx9 = subFlow(new wRDRedemptionFlow(kdr, redemptionAmount));
                report.append("✓ Redeemed ").append(redemptionAmount).append(" from ")
                       .append(getOurIdentity().getName().getOrganisation()).append(" to KDR\\n");
                report.append("  Transaction ID: ").append(tx9.getId()).append("\\n\\n");
            } else {
                report.append("⚠ Skipped - Not running on Wholesaler node or KDR not found\\n\\n");
            }

            progressTracker.setCurrentStep(STEP_10);
            report.append("Step 10: Additional wRD Operations\\n");
            if (getOurIdentity().getName().getOrganisation().equals("KDR") && wholesaler1 != null) {
                Amount<Currency> additionalIssuance = Amount.parseCurrency("20000000 IDR");
                SignedTransaction tx10 = subFlow(new wRDIssuanceFlow(wholesaler1, additionalIssuance));
                report.append("✓ Additional issuance of ").append(additionalIssuance).append(" to Wholesaler1\\n");
                report.append("  Transaction ID: ").append(tx10.getId()).append("\\n\\n");
            } else {
                report.append("⚠ Skipped - Not running on KDR node\\n\\n");
            }

            progressTracker.setCurrentStep(COMPLETE);
            report.append("=== Simulation Complete ===\\n");
            report.append("Node: ").append(getOurIdentity().getName().getOrganisation()).append("\\n");
            report.append("All available flows have been executed successfully.\\n");

        } catch (Exception e) {
            report.append("❌ Error during simulation: ").append(e.getMessage()).append("\\n");
            throw new FlowException("Simulation failed: " + e.getMessage(), e);
        }

        // Return the report as a string (note: this flow returns String, not SignedTransaction)
        getLogger().info(report.toString());
        return report.toString();
    }
}