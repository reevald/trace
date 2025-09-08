package com.trace.notary.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.corda.client.jackson.JacksonSupport;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.node.NodeInfo;
import net.corda.core.transactions.SignedTransaction;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@RestController
@RequestMapping("/api/notary")
public class MainController {
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);
    private final CordaRPCOps proxy;
    private final CordaX500Name me;

    public MainController(NodeRPCConnection rpc) {
        this.proxy = rpc.getProxy();
        this.me = proxy.nodeInfo().getLegalIdentities().get(0).getName();
    }

    public String toDisplayString(X500Name name) {
        return BCStyle.INSTANCE.toString(name);
    }

    private boolean isNotary(NodeInfo nodeInfo) {
        return !proxy.notaryIdentities()
                .stream().filter(el -> nodeInfo.isLegalIdentity(el))
                .collect(Collectors.toList()).isEmpty();
    }

    private boolean isMe(NodeInfo nodeInfo) {
        return nodeInfo.getLegalIdentities().get(0).getName().equals(me);
    }

    private boolean isNetworkMap(NodeInfo nodeInfo) {
        return nodeInfo.getLegalIdentities().get(0).getName().getOrganisation().equals("Network Map Service");
    }

    @Configuration
    class Plugin {
        @Bean
        public ObjectMapper registerModule() {
            return JacksonSupport.createNonRpcMapper();
        }
    }

    @GetMapping(value = "/status", produces = TEXT_PLAIN_VALUE)
    private String status() {
        return "200";
    }

    @GetMapping(value = "/servertime", produces = TEXT_PLAIN_VALUE)
    private String serverTime() {
        return (LocalDateTime.ofInstant(proxy.currentNodeTime(), ZoneId.of("UTC"))).toString();
    }

    @GetMapping(value = "/me", produces = APPLICATION_JSON_VALUE)
    private HashMap<String, String> whoami() {
        HashMap<String, String> myMap = new HashMap<>();
        myMap.put("me", me.toString());
        return myMap;
    }

    // TODO: Do something to this endpoint since only recorded when notary act as participant (tested)
    @GetMapping(value = "/notarized-transactions", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getNotarizedTransactions() {
        try {
            List<StateAndRef<ContractState>> allStates = proxy.vaultQuery(ContractState.class).getStates();
            
            List<Map<String, Object>> notarizedTransactions = new ArrayList<>();
            
            for (StateAndRef<ContractState> stateAndRef : allStates) {
                Map<String, Object> txInfo = new HashMap<>();
                
                txInfo.put("transactionId", stateAndRef.getRef().getTxhash().toString());
                txInfo.put("stateRef", stateAndRef.getRef().toString());
                txInfo.put("contractType", stateAndRef.getState().getData().getClass().getSimpleName());
                txInfo.put("notary", stateAndRef.getState().getNotary().getName().toString());
                
                List<String> participants = stateAndRef.getState().getData().getParticipants().stream()
                        .map(party -> party.toString())
                        .collect(Collectors.toList());
                txInfo.put("participants", participants);
                
                txInfo.put("encumbrance", stateAndRef.getState().getEncumbrance());
                txInfo.put("constraint", stateAndRef.getState().getConstraint().toString());
                
                try {
                    SignedTransaction signedTx = proxy.internalFindVerifiedTransaction(stateAndRef.getRef().getTxhash());
                    if (signedTx != null) {
                        txInfo.put("signatures", signedTx.getSigs().size());
                        txInfo.put("notarySignature", signedTx.getSigs().stream()
                                .anyMatch(sig -> proxy.notaryIdentities().stream()
                                        .anyMatch(notary -> notary.getOwningKey().equals(sig.getBy()))));
                    }
                } catch (Exception e) {
                    txInfo.put("signatureError", e.getMessage());
                }
                
                notarizedTransactions.add(txInfo);
            }

            return ResponseEntity.ok(notarizedTransactions);
        } catch (Exception e) {
            logger.error("Error retrieving notarized transactions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/notary-nodes", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getNotaryNodes() {
        try {
            List<Party> notaries = proxy.notaryIdentities();
            List<Map<String, Object>> notaryInfo = new ArrayList<>();
            
            for (Party notary : notaries) {
                Map<String, Object> info = new HashMap<>();
                info.put("name", notary.getName().toString());
                info.put("organisation", notary.getName().getOrganisation());
                info.put("locality", notary.getName().getLocality());
                info.put("country", notary.getName().getCountry());
                info.put("publicKey", notary.getOwningKey().toString());
                
                Optional<NodeInfo> nodeInfo = proxy.networkMapSnapshot().stream()
                        .filter(node -> node.isLegalIdentity(notary))
                        .findFirst();
                
                if (nodeInfo.isPresent()) {
                    info.put("addresses", nodeInfo.get().getAddresses().toString());
                    info.put("platformVersion", nodeInfo.get().getPlatformVersion());
                    info.put("serial", nodeInfo.get().getSerial());
                }
                
                notaryInfo.add(info);
            }

            return ResponseEntity.ok(notaryInfo);
        } catch (Exception e) {
            logger.error("Error retrieving notary nodes", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/transaction-details/{txId}", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTransactionDetails(@PathVariable String txId) {
        try {
            net.corda.core.crypto.SecureHash txHash = net.corda.core.crypto.SecureHash.parse(txId);
            SignedTransaction signedTx = proxy.internalFindVerifiedTransaction(txHash);
            
            if (signedTx == null) {
                return ResponseEntity.notFound().build();
            }
            
            Map<String, Object> txDetails = new HashMap<>();
            txDetails.put("transactionId", signedTx.getId().toString());
            txDetails.put("notary", signedTx.getNotary().getName().toString());
            
            txDetails.put("inputs", signedTx.getInputs().stream()
                    .map(input -> input.toString())
                    .collect(Collectors.toList()));
            
            txDetails.put("outputs", signedTx.getTx().getOutputStates().stream()
                    .map(output -> output.getClass().getSimpleName())
                    .collect(Collectors.toList()));
            
            txDetails.put("commands", signedTx.getTx().getCommands().stream()
                    .map(command -> command.getValue().getClass().getSimpleName())
                    .collect(Collectors.toList()));
            
            txDetails.put("signatures", signedTx.getSigs().stream()
                    .map(sig -> Map.of(
                            "by", sig.getBy().toString(),
                            "signature", sig.toString()
                    ))
                    .collect(Collectors.toList()));
            
            return ResponseEntity.ok(txDetails);
        } catch (Exception e) {
            logger.error("Error retrieving transaction details for " + txId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/network-map", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getNetworkMap() {
        try {
            List<NodeInfo> nodes = proxy.networkMapSnapshot();
            List<Map<String, Object>> networkInfo = new ArrayList<>();
            
            for (NodeInfo node : nodes) {
                Map<String, Object> nodeDetails = new HashMap<>();
                nodeDetails.put("legalIdentities", node.getLegalIdentities().stream()
                        .map(party -> party.getName().toString())
                        .collect(Collectors.toList()));
                nodeDetails.put("addresses", node.getAddresses().toString());
                nodeDetails.put("platformVersion", node.getPlatformVersion());
                nodeDetails.put("serial", node.getSerial());
                nodeDetails.put("isNotary", isNotary(node));
                
                networkInfo.add(nodeDetails);
            }
            
            return ResponseEntity.ok(networkInfo);
        } catch (Exception e) {
            logger.error("Error retrieving network map", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.emptyList());
        }
    }
}