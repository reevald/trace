package com.trace.observer.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trace.states.rRDAccountState;
import com.trace.states.wRDAccountState;
import net.corda.client.jackson.JacksonSupport;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.messaging.CordaRPCOps;
import net.corda.core.node.NodeInfo;
import net.corda.core.node.services.Vault;
import net.corda.core.node.services.vault.QueryCriteria;
import net.corda.core.transactions.SignedTransaction;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;
import static org.springframework.http.MediaType.TEXT_PLAIN_VALUE;

@RestController
@RequestMapping("/api/observer")
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

    @GetMapping(value = "/wrd-transactions", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getWRDTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "requestedDate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        try {
            List<StateAndRef<wRDAccountState>> wrdStates = proxy.vaultQuery(wRDAccountState.class).getStates();
            
            List<Map<String, Object>> transactions = new ArrayList<>();
            
            for (StateAndRef<wRDAccountState> stateAndRef : wrdStates) {
                wRDAccountState state = stateAndRef.getState().getData();
                Map<String, Object> txInfo = new HashMap<>();
                
                try {
                    SignedTransaction signedTx = proxy.internalFindVerifiedTransaction(stateAndRef.getRef().getTxhash());
                    if (signedTx != null) {
                        txInfo.put("requestedDate", Instant.ofEpochMilli(signedTx.getId().toString().hashCode()).toString());
                        txInfo.put("updatedDate", Instant.ofEpochMilli(signedTx.getId().toString().hashCode()).toString());
                    } else {
                        txInfo.put("requestedDate", Instant.now().toString());
                        txInfo.put("updatedDate", Instant.now().toString());
                    }
                } catch (Exception e) {
                    txInfo.put("requestedDate", Instant.now().toString());
                    txInfo.put("updatedDate", Instant.now().toString());
                }
                
                txInfo.put("typeTx", determineWRDTransactionType(state));
                txInfo.put("from", state.getIssuer().getName().toString());
                txInfo.put("to", state.getOwner().getName().toString());
                txInfo.put("amount", state.getTokenBalance().getQuantity());
                txInfo.put("status", "COMPLETED");
                txInfo.put("txId", stateAndRef.getRef().getTxhash().toString());
                
                transactions.add(txInfo);
            }
            
            List<Map<String, Object>> sortedTransactions = sortTransactions(transactions, sortBy, sortDir);
            
            Pageable pageable = PageRequest.of(page, size);
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), sortedTransactions.size());
            
            List<Map<String, Object>> pageContent = sortedTransactions.subList(start, end);
            Page<Map<String, Object>> pageResult = new PageImpl<>(pageContent, pageable, sortedTransactions.size());
            
            Map<String, Object> response = new HashMap<>();
            response.put("content", pageResult.getContent());
            response.put("totalElements", pageResult.getTotalElements());
            response.put("totalPages", pageResult.getTotalPages());
            response.put("currentPage", pageResult.getNumber());
            response.put("size", pageResult.getSize());
            response.put("hasNext", pageResult.hasNext());
            response.put("hasPrevious", pageResult.hasPrevious());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving wRD transactions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/rrd-transactions", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getRRDTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "requestedDate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        try {
            List<StateAndRef<rRDAccountState>> rrdStates = proxy.vaultQuery(rRDAccountState.class).getStates();
            
            List<Map<String, Object>> transactions = new ArrayList<>();
            
            for (StateAndRef<rRDAccountState> stateAndRef : rrdStates) {
                rRDAccountState state = stateAndRef.getState().getData();
                Map<String, Object> txInfo = new HashMap<>();
                
                try {
                    SignedTransaction signedTx = proxy.internalFindVerifiedTransaction(stateAndRef.getRef().getTxhash());
                    if (signedTx != null) {
                        txInfo.put("requestedDate", Instant.ofEpochMilli(signedTx.getId().toString().hashCode()).toString());
                        txInfo.put("updatedDate", Instant.ofEpochMilli(signedTx.getId().toString().hashCode()).toString());
                    } else {
                        txInfo.put("requestedDate", Instant.now().toString());
                        txInfo.put("updatedDate", Instant.now().toString());
                    }
                } catch (Exception e) {
                    txInfo.put("requestedDate", Instant.now().toString());
                    txInfo.put("updatedDate", Instant.now().toString());
                }
                
                txInfo.put("typeTx", determineRRDTransactionType(state));
                txInfo.put("from", state.getIssuer());
                txInfo.put("to", state.getOwner());
                txInfo.put("amount", state.getTokenBalance().getQuantity());
                txInfo.put("status", "COMPLETED");
                txInfo.put("txId", stateAndRef.getRef().getTxhash().toString());
                
                transactions.add(txInfo);
            }
            
            List<Map<String, Object>> sortedTransactions = sortTransactions(transactions, sortBy, sortDir);
            
            Pageable pageable = PageRequest.of(page, size);
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), sortedTransactions.size());
            
            List<Map<String, Object>> pageContent = sortedTransactions.subList(start, end);
            Page<Map<String, Object>> pageResult = new PageImpl<>(pageContent, pageable, sortedTransactions.size());
            
            Map<String, Object> response = new HashMap<>();
            response.put("content", pageResult.getContent());
            response.put("totalElements", pageResult.getTotalElements());
            response.put("totalPages", pageResult.getTotalPages());
            response.put("currentPage", pageResult.getNumber());
            response.put("size", pageResult.getSize());
            response.put("hasNext", pageResult.hasNext());
            response.put("hasPrevious", pageResult.hasPrevious());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving rRD transactions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/all-transactions", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getAllTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "requestedDate") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir) {
        
        try {
            List<StateAndRef<ContractState>> allStates = proxy.vaultQuery(ContractState.class).getStates();
            
            List<Map<String, Object>> transactions = new ArrayList<>();
            
            for (StateAndRef<ContractState> stateAndRef : allStates) {
                Map<String, Object> txInfo = new HashMap<>();
                
                try {
                    SignedTransaction signedTx = proxy.internalFindVerifiedTransaction(stateAndRef.getRef().getTxhash());
                    if (signedTx != null) {
                        txInfo.put("requestedDate", Instant.ofEpochMilli(signedTx.getId().toString().hashCode()).toString());
                        txInfo.put("updatedDate", Instant.ofEpochMilli(signedTx.getId().toString().hashCode()).toString());
                    } else {
                        txInfo.put("requestedDate", Instant.now().toString());
                        txInfo.put("updatedDate", Instant.now().toString());
                    }
                } catch (Exception e) {
                    txInfo.put("requestedDate", Instant.now().toString());
                    txInfo.put("updatedDate", Instant.now().toString());
                }
                
                if (stateAndRef.getState().getData() instanceof wRDAccountState) {
                    wRDAccountState wrdState = (wRDAccountState) stateAndRef.getState().getData();
                    txInfo.put("typeTx", "wRD_" + determineWRDTransactionType(wrdState));
                    txInfo.put("from", wrdState.getIssuer().getName().toString());
                    txInfo.put("to", wrdState.getOwner().getName().toString());
                    txInfo.put("amount", wrdState.getTokenBalance().getQuantity());
                } else if (stateAndRef.getState().getData() instanceof rRDAccountState) {
                    rRDAccountState rrdState = (rRDAccountState) stateAndRef.getState().getData();
                    txInfo.put("typeTx", "rRD_" + determineRRDTransactionType(rrdState));
                    txInfo.put("from", rrdState.getIssuer());
                    txInfo.put("to", rrdState.getOwner());
                    txInfo.put("amount", rrdState.getTokenBalance().getQuantity());
                } else {
                    txInfo.put("typeTx", "UNKNOWN");
                    txInfo.put("from", "UNKNOWN");
                    txInfo.put("to", "UNKNOWN");
                    txInfo.put("amount", 0);
                }
                
                txInfo.put("status", "COMPLETED");
                txInfo.put("txId", stateAndRef.getRef().getTxhash().toString());
                
                transactions.add(txInfo);
            }
            
            List<Map<String, Object>> sortedTransactions = sortTransactions(transactions, sortBy, sortDir);
            
            Pageable pageable = PageRequest.of(page, size);
            int start = (int) pageable.getOffset();
            int end = Math.min((start + pageable.getPageSize()), sortedTransactions.size());
            
            List<Map<String, Object>> pageContent = sortedTransactions.subList(start, end);
            Page<Map<String, Object>> pageResult = new PageImpl<>(pageContent, pageable, sortedTransactions.size());
            
            Map<String, Object> response = new HashMap<>();
            response.put("content", pageResult.getContent());
            response.put("totalElements", pageResult.getTotalElements());
            response.put("totalPages", pageResult.getTotalPages());
            response.put("currentPage", pageResult.getNumber());
            response.put("size", pageResult.getSize());
            response.put("hasNext", pageResult.hasNext());
            response.put("hasPrevious", pageResult.hasPrevious());
            
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error retrieving all transactions", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    private String determineWRDTransactionType(wRDAccountState state) {
        if (state.getOwner().equals(state.getIssuer())) {
            return "CENTRAL_INIT";
        } else if (state.getIssuer().getName().getOrganisation().equals("KDR")) {
            return "ISSUANCE";
        } else {
            return "TRANSFER";
        }
    }

    private String determineRRDTransactionType(rRDAccountState state) {
        if (state.isPeritel()) {
            return "PERITEL_ISSUANCE";
        } else if (state.isRetail()) {
            return "RETAIL_ISSUANCE";
        } else {
            return "CONVERSION";
        }
    }

    private List<Map<String, Object>> sortTransactions(List<Map<String, Object>> transactions, String sortBy, String sortDir) {
        Comparator<Map<String, Object>> comparator;
        
        switch (sortBy) {
            case "updatedDate":
                comparator = Comparator.comparing(tx -> (String) tx.get("updatedDate"));
                break;
            case "typeTx":
                comparator = Comparator.comparing(tx -> (String) tx.get("typeTx"));
                break;
            case "from":
                comparator = Comparator.comparing(tx -> (String) tx.get("from"));
                break;
            case "to":
                comparator = Comparator.comparing(tx -> (String) tx.get("to"));
                break;
            case "amount":
                comparator = Comparator.comparing(tx -> (Long) tx.get("amount"));
                break;
            case "status":
                comparator = Comparator.comparing(tx -> (String) tx.get("status"));
                break;
            case "txId":
                comparator = Comparator.comparing(tx -> (String) tx.get("txId"));
                break;
            default:
                comparator = Comparator.comparing(tx -> (String) tx.get("requestedDate"));
        }
        
        if ("desc".equalsIgnoreCase(sortDir)) {
            comparator = comparator.reversed();
        }
        
        return transactions.stream()
                .sorted(comparator)
                .collect(Collectors.toList());
    }
}