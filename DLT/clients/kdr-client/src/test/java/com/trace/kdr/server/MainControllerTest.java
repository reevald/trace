package com.trace.kdr.server;

import com.trace.states.wRDAccountState;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.node.NodeInfo;
import net.corda.core.node.services.vault.PageSpecification;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.node.services.vault.Sort;
import net.corda.core.node.services.vault.Vault;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class MainControllerTest {

    @Mock
    private NodeRPCConnection rpcConnection;

    @Mock
    private CordaRPCOps proxy;

    @Mock
    private NodeInfo nodeInfo;

    @Mock
    private Party kdrParty;

    private MainController mainController;

    @Before
    public void setUp() {
        when(rpcConnection.getProxy()).thenReturn(proxy);
        when(proxy.nodeInfo()).thenReturn(nodeInfo);
        when(nodeInfo.getLegalIdentities()).thenReturn(Collections.singletonList(kdrParty));
        when(kdrParty.getName()).thenReturn(CordaX500Name.parse("O=KDR,L=Jakarta,C=ID"));
        
        mainController = new MainController(rpcConnection);
    }

    @Test
    public void testStatus() {
        String result = mainController.status();
        assertEquals("200", result);
    }

    @Test
    public void testWhoami() {
        HashMap<String, String> result = mainController.whoami();
        assertNotNull(result);
        assertTrue(result.containsKey("me"));
        assertEquals("O=KDR,L=Jakarta,C=ID", result.get("me"));
    }

    @Test
    public void testGetTotalBalance() {
        Vault.Page<wRDAccountState> mockPage = mock(Vault.Page.class);
        StateAndRef<wRDAccountState> mockStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<wRDAccountState> mockTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        wRDAccountState mockState = mock(wRDAccountState.class);
        Amount<Currency> mockAmount = mock(Amount.class);

        when(proxy.vaultQuery(wRDAccountState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.singletonList(mockStateAndRef));
        when(mockStateAndRef.getState()).thenReturn(mockTransactionState);
        when(mockTransactionState.getData()).thenReturn(mockState);
        when(mockState.getOwner()).thenReturn(kdrParty);
        when(mockState.getIssuer()).thenReturn(kdrParty);
        when(mockState.getTokenBalance()).thenReturn(mockAmount);
        when(mockAmount.getQuantity()).thenReturn(1000000L);
        when(mockAmount.getToken()).thenReturn(Currency.getInstance("IDR"));

        ResponseEntity<Map<String, Object>> response = mainController.getTotalBalance();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1000000L, response.getBody().get("totalBalance"));
        assertEquals("IDR", response.getBody().get("currency"));
        assertEquals("UNCONSUMED", response.getBody().get("status"));
    }

    @Test
    public void testGetWalletList() {
        Vault.Page<wRDAccountState> mockPage = mock(Vault.Page.class);
        StateAndRef<wRDAccountState> mockStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<wRDAccountState> mockTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        wRDAccountState mockState = mock(wRDAccountState.class);
        Amount<Currency> mockAmount = mock(Amount.class);

        when(proxy.vaultQuery(wRDAccountState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.singletonList(mockStateAndRef));
        when(mockStateAndRef.getState()).thenReturn(mockTransactionState);
        when(mockTransactionState.getData()).thenReturn(mockState);
        when(mockState.getOwner()).thenReturn(kdrParty);
        when(mockState.getIssuer()).thenReturn(kdrParty);
        when(mockState.getWalletId()).thenReturn(net.corda.core.contracts.UniqueIdentifier.Companion.fromString("test-wallet-id"));
        when(mockState.getTokenBalance()).thenReturn(mockAmount);
        when(mockAmount.getQuantity()).thenReturn(1000000L);
        when(mockAmount.getToken()).thenReturn(Currency.getInstance("IDR"));
        when(mockState.getTokenType()).thenReturn("wRD");

        ResponseEntity<List<Map<String, Object>>> response = mainController.getWalletList();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty());
        
        Map<String, Object> wallet = response.getBody().get(0);
        assertEquals(1000000L, wallet.get("amount"));
        assertEquals("IDR", wallet.get("currency"));
        assertEquals("wRD", wallet.get("tokenType"));
    }

    @Test
    public void testCentralInit() {
        when(proxy.startTrackedFlowDynamic(any(), any())).thenReturn(mock(net.corda.core.flows.FlowHandle.class));
        
        ResponseEntity<String> response = mainController.centralInit(1000000L, "IDR");
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().contains("Central initialization completed"));
    }

    @Test
    public void testIssuanceInit() {
        Party wholesaler = mock(Party.class);
        when(proxy.wellKnownPartyFromX500Name(any())).thenReturn(wholesaler);
        when(proxy.startTrackedFlowDynamic(any(), any(), any())).thenReturn(mock(net.corda.core.flows.FlowHandle.class));
        
        ResponseEntity<String> response = mainController.issuanceInit("O=Wholesaler1,L=Jakarta,C=ID", 1000000L, "IDR");
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().contains("Issuance to"));
    }

    @Test
    public void testIssuanceInitUnknownWholesaler() {
        when(proxy.wellKnownPartyFromX500Name(any())).thenReturn(null);
        
        ResponseEntity<String> response = mainController.issuanceInit("O=Unknown,L=Jakarta,C=ID", 1000000L, "IDR");
        
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("Unknown wholesaler"));
    }

    @Test
    public void testIssuanceInitWithWalletId() {
        Party wholesaler = mock(Party.class);
        when(proxy.wellKnownPartyFromX500Name(any())).thenReturn(wholesaler);
        when(proxy.startTrackedFlowDynamic(any(), any(), any(), any())).thenReturn(mock(net.corda.core.flows.FlowHandle.class));
        
        ResponseEntity<String> response = mainController.issuanceInit(
            "O=Wholesaler1,L=Jakarta,C=ID", 
            1000000L, 
            "IDR", 
            "12345678-1234-1234-1234-123456789abc"
        );
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().contains("Issuance to"));
    }

    @Test
    public void testTransferWithWalletId() {
        Party receiver = mock(Party.class);
        when(proxy.wellKnownPartyFromX500Name(any())).thenReturn(receiver);
        when(proxy.startTrackedFlowDynamic(any(), any(), any(), any(), any())).thenReturn(mock(net.corda.core.flows.FlowHandle.class));
        
        ResponseEntity<String> response = mainController.transfer(
            "O=Wholesaler2,L=Surabaya,C=ID",
            1000000L,
            "IDR",
            "12345678-1234-1234-1234-123456789abc",
            "87654321-4321-4321-4321-cba987654321"
        );
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().contains("Transfer to"));
    }

    @Test
    public void testWrdToRrdWithWalletId() {
        when(proxy.startTrackedFlowDynamic(any(), any(), any(), any())).thenReturn(mock(net.corda.core.flows.FlowHandle.class));
        
        ResponseEntity<String> response = mainController.wrdToRrd(
            1000000L,
            "IDR",
            "12345678-1234-1234-1234-123456789abc",
            "11111111-2222-3333-4444-555555555555"
        );
        
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertTrue(response.getBody().contains("wRD to rRD conversion completed"));
    }

    @Test
    public void testGetWalletById() {
        Vault.Page<wRDAccountState> mockPage = mock(Vault.Page.class);
        StateAndRef<wRDAccountState> mockStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<wRDAccountState> mockTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        wRDAccountState mockState = mock(wRDAccountState.class);
        Amount<Currency> mockAmount = mock(Amount.class);
        net.corda.core.contracts.StateRef mockStateRef = mock(net.corda.core.contracts.StateRef.class);

        when(proxy.vaultQuery(eq(wRDAccountState.class), any())).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(java.util.Collections.singletonList(mockStateAndRef));
        when(mockStateAndRef.getState()).thenReturn(mockTransactionState);
        when(mockStateAndRef.getRef()).thenReturn(mockStateRef);
        when(mockTransactionState.getData()).thenReturn(mockState);
        when(mockState.getOwner()).thenReturn(kdrParty);
        when(mockState.getIssuer()).thenReturn(kdrParty);
        when(mockState.getWalletId()).thenReturn(net.corda.core.contracts.UniqueIdentifier.Companion.fromString("12345678-1234-1234-1234-123456789abc"));
        when(mockState.getTokenBalance()).thenReturn(mockAmount);
        when(mockAmount.getQuantity()).thenReturn(1000000L);
        when(mockAmount.getToken()).thenReturn(Currency.getInstance("IDR"));
        when(mockState.getTokenType()).thenReturn("wRD");
        when(mockStateRef.toString()).thenReturn("test-state-ref");

        ResponseEntity<Map<String, Object>> response = mainController.getWalletById("12345678-1234-1234-1234-123456789abc");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("12345678-1234-1234-1234-123456789abc", response.getBody().get("walletId"));
        assertEquals(1000000L, response.getBody().get("amount"));
        assertEquals("IDR", response.getBody().get("currency"));
        assertEquals("wRD", response.getBody().get("tokenType"));
    }

    @Test
    public void testGetAllWallets() {
        // Mock wRD states
        Vault.Page<wRDAccountState> mockWrdPage = mock(Vault.Page.class);
        StateAndRef<wRDAccountState> mockWrdStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<wRDAccountState> mockWrdTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        wRDAccountState mockWrdState = mock(wRDAccountState.class);
        Amount<Currency> mockWrdAmount = mock(Amount.class);

        when(proxy.vaultQuery(wRDAccountState.class)).thenReturn(mockWrdPage);
        when(mockWrdPage.getStates()).thenReturn(java.util.Collections.singletonList(mockWrdStateAndRef));
        when(mockWrdStateAndRef.getState()).thenReturn(mockWrdTransactionState);
        when(mockWrdTransactionState.getData()).thenReturn(mockWrdState);
        when(mockWrdState.getOwner()).thenReturn(kdrParty);
        when(mockWrdState.getWalletId()).thenReturn(net.corda.core.contracts.UniqueIdentifier.Companion.fromString("wrd-wallet-id"));
        when(mockWrdState.getTokenBalance()).thenReturn(mockWrdAmount);
        when(mockWrdAmount.getQuantity()).thenReturn(1000000L);
        when(mockWrdAmount.getToken()).thenReturn(Currency.getInstance("IDR"));

        // Mock rRD states
        Vault.Page<com.trace.states.rRDAccountState> mockRrdPage = mock(Vault.Page.class);
        when(proxy.vaultQuery(com.trace.states.rRDAccountState.class)).thenReturn(mockRrdPage);
        when(mockRrdPage.getStates()).thenReturn(java.util.Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = mainController.getAllWallets();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("wrdWallets"));
        assertTrue(response.getBody().containsKey("rrdWallets"));
        assertTrue(response.getBody().containsKey("totalWallets"));
    }
}