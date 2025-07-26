package com.trace.flows;

import com.trace.states.rRDAccountState;
import com.trace.states.wRDAccountState;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.transactions.SignedTransaction;
import net.corda.testing.node.MockNetwork;
import net.corda.testing.node.MockNetworkParameters;
import net.corda.testing.node.StartedMockNode;
import net.corda.testing.node.TestCordapp;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Currency;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class WalletIdFlowTest {

    private MockNetwork network;
    private StartedMockNode kdrNode;
    private StartedMockNode wholesalerNode;

    @Before
    public void setup() {
        network = new MockNetwork(new MockNetworkParameters(Arrays.asList(
                TestCordapp.findCordapp("com.trace.contracts"),
                TestCordapp.findCordapp("com.trace.flows")
        )));
        
        kdrNode = network.createPartyNode(CordaX500Name.parse("O=KDR,L=Jakarta,C=ID"));
        wholesalerNode = network.createPartyNode(CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));
        
        network.runNetwork();
    }

    @After
    public void tearDown() {
        network.stopNodes();
    }

    @Test
    public void testWRD2rRDIssuanceInitFlowWithWalletId() throws Exception {
        // Setup: Create initial wRD state
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        wRDCentralInitFlow centralFlow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> centralFuture = kdrNode.startFlow(centralFlow);
        network.runNetwork();
        SignedTransaction centralTx = centralFuture.get();
        
        // Issue wRD to wholesaler
        Amount<Currency> issuanceAmount = new Amount<>(10000000000L, Currency.getInstance("IDR"));
        wRDAccountState centralState = (wRDAccountState) centralTx.getTx().getOutputStates().get(0);
        UniqueIdentifier kdrWalletId = centralState.getWalletId();
        
        wRDIssuanceInitFlow issuanceFlow = new wRDIssuanceInitFlow(
            wholesalerNode.getInfo().getLegalIdentities().get(0), 
            issuanceAmount, 
            kdrWalletId
        );
        Future<SignedTransaction> issuanceFuture = kdrNode.startFlow(issuanceFlow);
        network.runNetwork();
        SignedTransaction issuanceTx = issuanceFuture.get();
        
        // Get wholesaler's wRD walletId
        wRDAccountState wholesalerState = (wRDAccountState) issuanceTx.getTx().getOutputStates().get(1);
        UniqueIdentifier wholesalerWalletId = wholesalerState.getWalletId();
        
        // Test wRD to rRD conversion with specific walletIds
        Amount<Currency> conversionAmount = new Amount<>(1000000000L, Currency.getInstance("IDR"));
        UniqueIdentifier targetRRDWalletId = new UniqueIdentifier();
        
        wRD2rRDIssuanceInitFlow conversionFlow = new wRD2rRDIssuanceInitFlow(
            conversionAmount,
            wholesalerWalletId,
            targetRRDWalletId
        );
        
        Future<SignedTransaction> conversionFuture = wholesalerNode.startFlow(conversionFlow);
        network.runNetwork();
        SignedTransaction conversionTx = conversionFuture.get();
        
        assertNotNull(conversionTx);
        assertEquals(2, conversionTx.getTx().getOutputStates().size()); // Updated wRD state + new rRD state
        
        // Verify the rRD state was created with correct walletId
        rRDAccountState rrdState = null;
        for (Object state : conversionTx.getTx().getOutputStates()) {
            if (state instanceof rRDAccountState) {
                rrdState = (rRDAccountState) state;
                break;
            }
        }
        
        assertNotNull(rrdState);
        assertEquals(targetRRDWalletId, rrdState.getWalletId());
        assertEquals(conversionAmount, rrdState.getTokenBalance());
        assertTrue(rrdState.isPeritel());
    }

    @Test
    public void testRRDIssuanceInitFlowWithWalletId() throws Exception {
        // Setup: Create rRD peritel state first
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        wRDCentralInitFlow centralFlow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> centralFuture = kdrNode.startFlow(centralFlow);
        network.runNetwork();
        SignedTransaction centralTx = centralFuture.get();
        
        // Issue wRD to wholesaler
        Amount<Currency> issuanceAmount = new Amount<>(10000000000L, Currency.getInstance("IDR"));
        wRDAccountState centralState = (wRDAccountState) centralTx.getTx().getOutputStates().get(0);
        UniqueIdentifier kdrWalletId = centralState.getWalletId();
        
        wRDIssuanceInitFlow issuanceFlow = new wRDIssuanceInitFlow(
            wholesalerNode.getInfo().getLegalIdentities().get(0), 
            issuanceAmount, 
            kdrWalletId
        );
        Future<SignedTransaction> issuanceFuture = kdrNode.startFlow(issuanceFlow);
        network.runNetwork();
        SignedTransaction issuanceTx = issuanceFuture.get();
        
        // Convert wRD to rRD (peritel)
        wRDAccountState wholesalerState = (wRDAccountState) issuanceTx.getTx().getOutputStates().get(1);
        UniqueIdentifier wholesalerWalletId = wholesalerState.getWalletId();
        
        Amount<Currency> conversionAmount = new Amount<>(5000000000L, Currency.getInstance("IDR"));
        UniqueIdentifier peritelWalletId = new UniqueIdentifier();
        
        wRD2rRDIssuanceInitFlow conversionFlow = new wRD2rRDIssuanceInitFlow(
            conversionAmount,
            wholesalerWalletId,
            peritelWalletId
        );
        Future<SignedTransaction> conversionFuture = wholesalerNode.startFlow(conversionFlow);
        network.runNetwork();
        SignedTransaction conversionTx = conversionFuture.get();
        
        // Now test rRD issuance (peritel to retail) with walletId
        Amount<Currency> retailAmount = new Amount<>(1000000000L, Currency.getInstance("IDR"));
        UniqueIdentifier retailWalletId = new UniqueIdentifier();
        
        rRDIssuanceInitFlow retailFlow = new rRDIssuanceInitFlow(
            retailAmount,
            peritelWalletId,
            retailWalletId
        );
        
        Future<SignedTransaction> retailFuture = wholesalerNode.startFlow(retailFlow);
        network.runNetwork();
        SignedTransaction retailTx = retailFuture.get();
        
        assertNotNull(retailTx);
        assertEquals(2, retailTx.getTx().getOutputStates().size()); // Updated peritel state + new retail state
        
        // Verify the retail rRD state was created with correct walletId
        rRDAccountState retailState = null;
        for (Object state : retailTx.getTx().getOutputStates()) {
            if (state instanceof rRDAccountState) {
                rRDAccountState rrdState = (rRDAccountState) state;
                if (rrdState.isRetail()) {
                    retailState = rrdState;
                    break;
                }
            }
        }
        
        assertNotNull(retailState);
        assertEquals(retailWalletId, retailState.getWalletId());
        assertEquals(retailAmount, retailState.getTokenBalance());
        assertTrue(retailState.isRetail());
    }

    @Test
    public void testWalletIdValidation() throws Exception {
        // Test that flows properly validate walletId existence
        UniqueIdentifier nonExistentWalletId = new UniqueIdentifier();
        Amount<Currency> amount = new Amount<>(1000000000L, Currency.getInstance("IDR"));
        
        // This should fail because the walletId doesn't exist
        wRDIssuanceInitFlow invalidFlow = new wRDIssuanceInitFlow(
            wholesalerNode.getInfo().getLegalIdentities().get(0),
            amount,
            nonExistentWalletId
        );
        
        try {
            Future<SignedTransaction> future = kdrNode.startFlow(invalidFlow);
            network.runNetwork();
            future.get();
            fail("Expected FlowException for non-existent walletId");
        } catch (Exception e) {
            // Expected - the flow should fail with a descriptive error
            assertTrue(e.getMessage().contains("not found") || 
                      e.getCause().getMessage().contains("not found"));
        }
    }

    @Test
    public void testBackwardCompatibility() throws Exception {
        // Test that old constructors still work
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        
        // Test old constructor without walletId
        wRDCentralInitFlow centralFlow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> centralFuture = kdrNode.startFlow(centralFlow);
        network.runNetwork();
        SignedTransaction centralTx = centralFuture.get();
        
        assertNotNull(centralTx);
        assertEquals(1, centralTx.getTx().getOutputStates().size());
        
        // Test old issuance constructor
        Amount<Currency> issuanceAmount = new Amount<>(10000000000L, Currency.getInstance("IDR"));
        wRDIssuanceInitFlow oldIssuanceFlow = new wRDIssuanceInitFlow(
            wholesalerNode.getInfo().getLegalIdentities().get(0), 
            issuanceAmount
        );
        
        Future<SignedTransaction> issuanceFuture = kdrNode.startFlow(oldIssuanceFlow);
        network.runNetwork();
        SignedTransaction issuanceTx = issuanceFuture.get();
        
        assertNotNull(issuanceTx);
        assertEquals(2, issuanceTx.getTx().getOutputStates().size());
    }
}