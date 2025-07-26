package com.trace.flows;

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

class wRDCentralInitFlowTest {

    private MockNetwork network;
    private StartedMockNode kdrNode;

    @Before
    public void setup() {
        network = new MockNetwork(new MockNetworkParameters(Arrays.asList(
                TestCordapp.findCordapp("com.trace.contracts"),
                TestCordapp.findCordapp("com.trace.flows")
        )));
        
        kdrNode = network.createPartyNode(CordaX500Name.parse("O=KDR,L=Jakarta,C=ID"));
        
        network.runNetwork();
    }

    @After
    public void tearDown() {
        network.stopNodes();
    }

    @Test
    public void testWRDCentralInitFlow() throws Exception {
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        
        wRDCentralInitFlow flow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> future = kdrNode.startFlow(flow);
        network.runNetwork();
        
        SignedTransaction signedTx = future.get();
        
        assertNotNull(signedTx);
        assertEquals(1, signedTx.getTx().getOutputStates().size());
        
        wRDAccountState outputState = (wRDAccountState) signedTx.getTx().getOutputStates().get(0);
        assertEquals(kdrNode.getInfo().getLegalIdentities().get(0), outputState.getOwner());
        assertEquals(kdrNode.getInfo().getLegalIdentities().get(0), outputState.getIssuer());
        assertEquals(initialAmount, outputState.getTokenBalance());
        assertEquals("IDR", outputState.getTokenType());
    }

    @Test
    public void testWRDCentralInitFlowWithZeroAmount() throws Exception {
        Amount<Currency> zeroAmount = new Amount<>(0L, Currency.getInstance("IDR"));
        
        wRDCentralInitFlow flow = new wRDCentralInitFlow(zeroAmount);
        Future<SignedTransaction> future = kdrNode.startFlow(flow);
        network.runNetwork();
        
        SignedTransaction signedTx = future.get();
        
        assertNotNull(signedTx);
        wRDAccountState outputState = (wRDAccountState) signedTx.getTx().getOutputStates().get(0);
        assertEquals(zeroAmount, outputState.getTokenBalance());
    }

    @Test
    public void testWRDCentralInitFlowParticipants() throws Exception {
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        
        wRDCentralInitFlow flow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> future = kdrNode.startFlow(flow);
        network.runNetwork();
        
        SignedTransaction signedTx = future.get();
        wRDAccountState outputState = (wRDAccountState) signedTx.getTx().getOutputStates().get(0);
        
        assertEquals(2, outputState.getParticipants().size());
        assertTrue(outputState.getParticipants().contains(kdrNode.getInfo().getLegalIdentities().get(0)));
    }

    @Test
    public void testWRDIssuanceInitFlowWithWalletId() throws Exception {
        // First create a central init to have a source wallet
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        wRDCentralInitFlow centralFlow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> centralFuture = kdrNode.startFlow(centralFlow);
        network.runNetwork();
        SignedTransaction centralTx = centralFuture.get();
        
        // Get the walletId from the central init
        wRDAccountState centralState = (wRDAccountState) centralTx.getTx().getOutputStates().get(0);
        UniqueIdentifier sourceWalletId = centralState.getWalletId();
        
        // Create a wholesaler node for testing
        StartedMockNode wholesalerNode = network.createPartyNode(CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));
        
        // Test issuance with specific walletId
        Amount<Currency> issuanceAmount = new Amount<>(10000000000L, Currency.getInstance("IDR"));
        wRDIssuanceInitFlow issuanceFlow = new wRDIssuanceInitFlow(
            wholesalerNode.getInfo().getLegalIdentities().get(0), 
            issuanceAmount, 
            sourceWalletId
        );
        
        Future<SignedTransaction> issuanceFuture = kdrNode.startFlow(issuanceFlow);
        network.runNetwork();
        SignedTransaction issuanceTx = issuanceFuture.get();
        
        assertNotNull(issuanceTx);
        assertEquals(2, issuanceTx.getTx().getOutputStates().size()); // KDR updated state + new wholesaler state
        
        // Verify the wholesaler received the correct amount
        wRDAccountState wholesalerState = (wRDAccountState) issuanceTx.getTx().getOutputStates().get(1);
        assertEquals(wholesalerNode.getInfo().getLegalIdentities().get(0), wholesalerState.getOwner());
        assertEquals(issuanceAmount, wholesalerState.getTokenBalance());
    }

    @Test
    public void testWRDTransferFlowWithWalletId() throws Exception {
        // Setup: Create initial states
        Amount<Currency> initialAmount = new Amount<>(100000000000000L, Currency.getInstance("IDR"));
        wRDCentralInitFlow centralFlow = new wRDCentralInitFlow(initialAmount);
        Future<SignedTransaction> centralFuture = kdrNode.startFlow(centralFlow);
        network.runNetwork();
        SignedTransaction centralTx = centralFuture.get();
        
        wRDAccountState centralState = (wRDAccountState) centralTx.getTx().getOutputStates().get(0);
        UniqueIdentifier kdrWalletId = centralState.getWalletId();
        
        // Create wholesaler nodes
        StartedMockNode wholesaler1Node = network.createPartyNode(CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));
        StartedMockNode wholesaler2Node = network.createPartyNode(CordaX500Name.parse("O=Wholesaler2,L=Surabaya,C=ID"));
        
        // Issue to wholesaler1 first
        Amount<Currency> issuanceAmount = new Amount<>(10000000000L, Currency.getInstance("IDR"));
        wRDIssuanceInitFlow issuanceFlow = new wRDIssuanceInitFlow(
            wholesaler1Node.getInfo().getLegalIdentities().get(0), 
            issuanceAmount, 
            kdrWalletId
        );
        Future<SignedTransaction> issuanceFuture = kdrNode.startFlow(issuanceFlow);
        network.runNetwork();
        SignedTransaction issuanceTx = issuanceFuture.get();
        
        // Get wholesaler1's walletId
        wRDAccountState wholesaler1State = (wRDAccountState) issuanceTx.getTx().getOutputStates().get(1);
        UniqueIdentifier wholesaler1WalletId = wholesaler1State.getWalletId();
        
        // Create a wallet for wholesaler2 (simulate existing wallet)
        UniqueIdentifier wholesaler2WalletId = new UniqueIdentifier();
        
        // Test transfer with specific walletIds
        Amount<Currency> transferAmount = new Amount<>(1000000000L, Currency.getInstance("IDR"));
        wRDTransferFlow transferFlow = new wRDTransferFlow(
            wholesaler2Node.getInfo().getLegalIdentities().get(0),
            transferAmount,
            wholesaler1WalletId,
            wholesaler2WalletId
        );
        
        Future<SignedTransaction> transferFuture = wholesaler1Node.startFlow(transferFlow);
        network.runNetwork();
        SignedTransaction transferTx = transferFuture.get();
        
        assertNotNull(transferTx);
        // Verify the transfer created the expected states
        assertTrue(transferTx.getTx().getOutputStates().size() >= 2);
    }
}