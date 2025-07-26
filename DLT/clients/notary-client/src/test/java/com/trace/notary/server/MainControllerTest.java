package com.trace.notary.server;

import net.corda.core.contracts.ContractState;
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
    private Party notaryParty;

    private MainController mainController;

    @Before
    public void setUp() {
        when(rpcConnection.getProxy()).thenReturn(proxy);
        when(proxy.nodeInfo()).thenReturn(nodeInfo);
        when(nodeInfo.getLegalIdentities()).thenReturn(Collections.singletonList(notaryParty));
        when(notaryParty.getName()).thenReturn(CordaX500Name.parse("O=Notary,L=Jakarta,C=ID"));
        
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
        assertEquals("O=Notary,L=Jakarta,C=ID", result.get("me"));
    }

    @Test
    public void testGetNotarizedTransactions() {
        Vault.Page<ContractState> mockPage = mock(Vault.Page.class);
        StateAndRef<ContractState> mockStateAndRef = mock(StateAndRef.class);
        net.corda.core.contracts.TransactionState<ContractState> mockTransactionState = mock(net.corda.core.contracts.TransactionState.class);
        ContractState mockState = mock(ContractState.class);
        net.corda.core.contracts.StateRef mockStateRef = mock(net.corda.core.contracts.StateRef.class);
        net.corda.core.crypto.SecureHash mockTxHash = mock(net.corda.core.crypto.SecureHash.class);

        when(proxy.vaultQuery(ContractState.class)).thenReturn(mockPage);
        when(mockPage.getStates()).thenReturn(Collections.singletonList(mockStateAndRef));
        when(mockStateAndRef.getState()).thenReturn(mockTransactionState);
        when(mockStateAndRef.getRef()).thenReturn(mockStateRef);
        when(mockStateRef.getTxhash()).thenReturn(mockTxHash);
        when(mockTxHash.toString()).thenReturn("test-tx-hash");
        when(mockTransactionState.getData()).thenReturn(mockState);
        when(mockTransactionState.getNotary()).thenReturn(notaryParty);
        when(mockState.getParticipants()).thenReturn(Collections.emptyList());

        ResponseEntity<List<Map<String, Object>>> response = mainController.getNotarizedTransactions();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
    }

    @Test
    public void testGetNotaryNodes() {
        when(proxy.notaryIdentities()).thenReturn(Collections.singletonList(notaryParty));
        when(proxy.networkMapSnapshot()).thenReturn(Collections.singletonList(nodeInfo));
        when(nodeInfo.isLegalIdentity(notaryParty)).thenReturn(true);
        when(nodeInfo.getAddresses()).thenReturn(Collections.emptyList());
        when(nodeInfo.getPlatformVersion()).thenReturn(4);
        when(nodeInfo.getSerial()).thenReturn(1L);
        when(notaryParty.getOwningKey()).thenReturn(mock(java.security.PublicKey.class));

        ResponseEntity<List<Map<String, Object>>> response = mainController.getNotaryNodes();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty());
        
        Map<String, Object> notaryInfo = response.getBody().get(0);
        assertEquals("O=Notary,L=Jakarta,C=ID", notaryInfo.get("name"));
        assertEquals("Notary", notaryInfo.get("organisation"));
        assertEquals("Jakarta", notaryInfo.get("locality"));
        assertEquals("ID", notaryInfo.get("country"));
    }

    @Test
    public void testGetNetworkMap() {
        when(proxy.networkMapSnapshot()).thenReturn(Collections.singletonList(nodeInfo));
        when(nodeInfo.getLegalIdentities()).thenReturn(Collections.singletonList(notaryParty));
        when(nodeInfo.getAddresses()).thenReturn(Collections.emptyList());
        when(nodeInfo.getPlatformVersion()).thenReturn(4);
        when(nodeInfo.getSerial()).thenReturn(1L);
        when(proxy.notaryIdentities()).thenReturn(Collections.singletonList(notaryParty));

        ResponseEntity<List<Map<String, Object>>> response = mainController.getNetworkMap();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isEmpty());
        
        Map<String, Object> nodeDetails = response.getBody().get(0);
        assertTrue(nodeDetails.containsKey("legalIdentities"));
        assertTrue(nodeDetails.containsKey("platformVersion"));
        assertTrue(nodeDetails.containsKey("isNotary"));
    }
}