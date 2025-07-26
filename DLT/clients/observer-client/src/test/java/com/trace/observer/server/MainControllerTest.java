package com.trace.observer.server;

import com.trace.states.rRDAccountState;
import com.trace.states.wRDAccountState;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.node.NodeInfo;
import net.corda.core.node.services.vault.Vault;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.Assert.*;
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
    private Party observerParty;

    private MainController mainController;

    @Before
    public void setUp() {
        when(rpcConnection.getProxy()).thenReturn(proxy);
        when(proxy.nodeInfo()).thenReturn(nodeInfo);
        when(nodeInfo.getLegalIdentities()).thenReturn(Collections.singletonList(observerParty));
        when(observerParty.getName()).thenReturn(CordaX500Name.parse("O=Observer,L=Jakarta,C=ID"));
        
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
        assertEquals("O=Observer,L=Jakarta,C=ID", result.get("me"));
    }

    @Test
    public void testGetWRDTransactions() {
        Vault.Page<wRDAccountState> mockPage = mock(Vault.Page.class);
        StateAndRef<wRDAccountState> mockStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<wRDAccountState> mockTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        wRDAccountState mockState = mock(wRDAccountState.class);
        Amount<Currency> mockAmount = mock(Amount.class);
        net.corda.core.contracts.StateRef mockStateRef = mock(net.corda.core.contracts.StateRef.class);
        net.corda.core.crypto.SecureHash mockTxHash = mock(net.corda.core.crypto.SecureHash.class);
        Party mockIssuer = mock(Party.class);
        Party mockOwner = mock(Party.class);

        when(proxy.vaultQuery(wRDAccountState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.singletonList(mockStateAndRef));
        when(mockStateAndRef.getState()).thenReturn(mockTransactionState);
        when(mockStateAndRef.getRef()).thenReturn(mockStateRef);
        when(mockStateRef.getTxhash()).thenReturn(mockTxHash);
        when(mockTxHash.toString()).thenReturn("test-tx-hash");
        when(mockTransactionState.getData()).thenReturn(mockState);
        when(mockState.getTokenBalance()).thenReturn(mockAmount);
        when(mockAmount.getQuantity()).thenReturn(1000000L);
        when(mockState.getIssuer()).thenReturn(mockIssuer);
        when(mockState.getOwner()).thenReturn(mockOwner);
        when(mockIssuer.getName()).thenReturn(CordaX500Name.parse("O=KDR,L=Jakarta,C=ID"));
        when(mockOwner.getName()).thenReturn(CordaX500Name.parse("O=Wholesaler1,L=Jakarta,C=ID"));

        ResponseEntity<Map<String, Object>> response = mainController.getWRDTransactions(0, 10, "requestedDate", "desc");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("content"));
        assertTrue(response.getBody().containsKey("totalElements"));
        assertTrue(response.getBody().containsKey("totalPages"));
    }

    @Test
    public void testGetRRDTransactions() {
        Vault.Page<rRDAccountState> mockPage = mock(Vault.Page.class);
        StateAndRef<rRDAccountState> mockStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<rRDAccountState> mockTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        rRDAccountState mockState = mock(rRDAccountState.class);
        Amount<Currency> mockAmount = mock(Amount.class);
        net.corda.core.contracts.StateRef mockStateRef = mock(net.corda.core.contracts.StateRef.class);
        net.corda.core.crypto.SecureHash mockTxHash = mock(net.corda.core.crypto.SecureHash.class);

        when(proxy.vaultQuery(rRDAccountState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.singletonList(mockStateAndRef));
        when(mockStateAndRef.getState()).thenReturn(mockTransactionState);
        when(mockStateAndRef.getRef()).thenReturn(mockStateRef);
        when(mockStateRef.getTxhash()).thenReturn(mockTxHash);
        when(mockTxHash.toString()).thenReturn("test-tx-hash");
        when(mockTransactionState.getData()).thenReturn(mockState);
        when(mockState.getTokenBalance()).thenReturn(mockAmount);
        when(mockAmount.getQuantity()).thenReturn(1000000L);
        when(mockState.getIssuer()).thenReturn("KDR");
        when(mockState.getOwner()).thenReturn("Wholesaler1-P-peritel-001");
        when(mockState.isPeritel()).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = mainController.getRRDTransactions(0, 10, "requestedDate", "desc");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("content"));
        assertTrue(response.getBody().containsKey("totalElements"));
        assertTrue(response.getBody().containsKey("totalPages"));
    }

    @Test
    public void testGetWRDTransactionsWithPagination() {
        Vault.Page<wRDAccountState> mockPage = mock(Vault.Page.class);
        when(proxy.vaultQuery(wRDAccountState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = mainController.getWRDTransactions(1, 5, "amount", "asc");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().get("currentPage"));
        assertEquals(5, response.getBody().get("size"));
    }

    @Test
    public void testGetRRDTransactionsWithSorting() {
        Vault.Page<rRDAccountState> mockPage = mock(Vault.Page.class);
        when(proxy.vaultQuery(rRDAccountState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.emptyList());

        ResponseEntity<Map<String, Object>> response = mainController.getRRDTransactions(0, 10, "typeTx", "asc");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertTrue(response.getBody().containsKey("content"));
    }
}