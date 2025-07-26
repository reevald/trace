package com.trace.kdr.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trace.flows.*;
import com.trace.states.wRDAccountState;
import com.trace.states.rRDAccountState;
import net.corda.client.jackson.JacksonSupport;
import net.corda.core.contracts.Amount;
import net.corda.core.contracts.ContractState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.identity.CordaX500Name;
import net.corda.core.identity.Party;
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
@RequestMapping("/api/kdr")
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

    @GetMapping(value = "/total-balance", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getTotalBalance() {
        try {
            QueryCriteria queryCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
            List<StateAndRef<wRDAccountState>> kdrStates = proxy.vaultQuery(wRDAccountState.class)
                    .getStates()
                    .stream()
                    .filter(stateAndRef -> {
                        wRDAccountState state = stateAndRef.getState().getData();
                        return state.getOwner().getName().equals(me) && state.getIssuer().getName().equals(me);
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            if (!kdrStates.isEmpty()) {
                Amount<Currency> totalBalance = kdrStates.get(0).getState().getData().getTokenBalance();
                response.put("totalBalance", totalBalance.getQuantity());
                response.put("currency", totalBalance.getToken().getCurrencyCode());
                response.put("status", "UNCONSUMED");
            } else {
                response.put("totalBalance", 0);
                response.put("currency", "IDR");
                response.put("status", "NO_BALANCE");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/wallets", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getWalletList() {
        try {
            QueryCriteria queryCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
            List<StateAndRef<wRDAccountState>> kdrStates = proxy.vaultQuery(wRDAccountState.class)
                    .getStates()
                    .stream()
                    .filter(stateAndRef -> {
                        wRDAccountState state = stateAndRef.getState().getData();
                        return state.getOwner().getName().equals(me);
                    })
                    .collect(Collectors.toList());

            List<Map<String, Object>> wallets = kdrStates.stream()
                    .map(stateAndRef -> {
                        wRDAccountState state = stateAndRef.getState().getData();
                        Map<String, Object> wallet = new HashMap<>();
                        wallet.put("walletId", state.getWalletId().toString());
                        wallet.put("owner", state.getOwner().getName().toString());
                        wallet.put("issuer", state.getIssuer().getName().toString());
                        wallet.put("amount", state.getTokenBalance().getQuantity());
                        wallet.put("currency", state.getTokenBalance().getToken().getCurrencyCode());
                        wallet.put("tokenType", state.getTokenType());
                        wallet.put("stateRef", stateAndRef.getRef().toString());
                        return wallet;
                    })
                    .collect(Collectors.toList());

            return ResponseEntity.ok(wallets);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.emptyList());
        }
    }

    @GetMapping(value = "/wallet/{walletId}", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getWalletById(@PathVariable String walletId) {
        try {
            UniqueIdentifier uniqueId = UniqueIdentifier.Companion.fromString(walletId);
            QueryCriteria generalCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
            QueryCriteria linearIdCriteria = new QueryCriteria.LinearStateQueryCriteria(null, Collections.singletonList(uniqueId.getId()));
            QueryCriteria combinedCriteria = generalCriteria.and(linearIdCriteria);
            
            List<StateAndRef<wRDAccountState>> walletStates = proxy.vaultQueryByCriteria(combinedCriteria, wRDAccountState.class)
                    .getStates()
                    .stream()
                    .filter(stateAndRef -> {
                        wRDAccountState state = stateAndRef.getState().getData();
                        return state.getOwner().getName().equals(me);
                    })
                    .collect(Collectors.toList());

            if (walletStates.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            StateAndRef<wRDAccountState> walletStateAndRef = walletStates.get(0);
            wRDAccountState state = walletStateAndRef.getState().getData();
            Map<String, Object> wallet = new HashMap<>();
            wallet.put("walletId", state.getWalletId().toString());
            wallet.put("owner", state.getOwner().getName().toString());
            wallet.put("issuer", state.getIssuer().getName().toString());
            wallet.put("amount", state.getTokenBalance().getQuantity());
            wallet.put("currency", state.getTokenBalance().getToken().getCurrencyCode());
            wallet.put("tokenType", state.getTokenType());
            wallet.put("stateRef", walletStateAndRef.getRef().toString());

            return ResponseEntity.ok(wallet);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping(value = "/transactions", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<List<Map<String, Object>>> getTransactions() {
        try {
            List<StateAndRef<ContractState>> allStates = proxy.vaultQuery(ContractState.class).getStates();
            
            List<Map<String, Object>> transactions = new ArrayList<>();
            
            for (StateAndRef<ContractState> stateAndRef : allStates) {
                Map<String, Object> txInfo = new HashMap<>();
                txInfo.put("stateRef", stateAndRef.getRef().toString());
                txInfo.put("contractType", stateAndRef.getState().getData().getClass().getSimpleName());
                txInfo.put("participants", stateAndRef.getState().getData().getParticipants().stream()
                        .map(party -> party.toString())
                        .collect(Collectors.toList()));
                
                if (stateAndRef.getState().getData() instanceof wRDAccountState) {
                    wRDAccountState wrdState = (wRDAccountState) stateAndRef.getState().getData();
                    txInfo.put("transactionType", "wRD");
                    txInfo.put("amount", wrdState.getTokenBalance().getQuantity());
                    txInfo.put("currency", wrdState.getTokenBalance().getToken().getCurrencyCode());
                    txInfo.put("owner", wrdState.getOwner().getName().toString());
                    txInfo.put("issuer", wrdState.getIssuer().getName().toString());
                }
                
                transactions.add(txInfo);
            }

            return ResponseEntity.ok(transactions);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Collections.emptyList());
        }
    }

    @PostMapping(value = "/central-init", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> centralInit(@RequestParam(value = "amount") long amount,
                                              @RequestParam(value = "currency") String currency) {
        try {
            Amount<Currency> initialAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result = proxy.startTrackedFlowDynamic(wRDCentralInitFlow.class, initialAmount)
                    .getReturnValue().get();
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("Central initialization completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/issuance-init", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> issuanceInit(@RequestParam(value = "wholesaler") String wholesalerName,
                                               @RequestParam(value = "amount") long amount,
                                               @RequestParam(value = "currency") String currency,
                                               @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId) {
        try {
            Party wholesaler = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(wholesalerName));
            if (wholesaler == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unknown wholesaler: " + wholesalerName);
            }
            
            Amount<Currency> issuanceAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if (sourceWalletId != null && !sourceWalletId.trim().isEmpty()) {
                UniqueIdentifier walletId = UniqueIdentifier.Companion.fromString(sourceWalletId);
                result = proxy.startTrackedFlowDynamic(wRDIssuanceInitFlow.class, wholesaler, issuanceAmount, walletId)
                        .getReturnValue().get();
            } else {
                result = proxy.startTrackedFlowDynamic(wRDIssuanceInitFlow.class, wholesaler, issuanceAmount)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("Issuance to " + wholesalerName + " completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/issuance", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> issuance(@RequestParam(value = "receiver") String receiverName,
                                           @RequestParam(value = "amount") long amount,
                                           @RequestParam(value = "currency") String currency,
                                           @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId) {
        try {
            Party receiver = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(receiverName));
            if (receiver == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unknown receiver: " + receiverName);
            }
            
            Amount<Currency> issuanceAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if (sourceWalletId != null && !sourceWalletId.trim().isEmpty()) {
                UniqueIdentifier walletId = UniqueIdentifier.Companion.fromString(sourceWalletId);
                result = proxy.startTrackedFlowDynamic(wRDIssuanceFlow.class, receiver, issuanceAmount, walletId)
                        .getReturnValue().get();
            } else {
                result = proxy.startTrackedFlowDynamic(wRDIssuanceFlow.class, receiver, issuanceAmount)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("Issuance to " + receiverName + " completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @GetMapping(value = "/peers", produces = APPLICATION_JSON_VALUE)
    public HashMap<String, List<String>> getPeers() {
        HashMap<String, List<String>> myMap = new HashMap<>();
        Stream<NodeInfo> filteredNodes = proxy.networkMapSnapshot().stream()
                .filter(el -> !isNotary(el) && !isMe(el) && !isNetworkMap(el));
        List<String> nodeNames = filteredNodes.map(el -> el.getLegalIdentities().get(0).getName().toString())
                .collect(Collectors.toList());
        myMap.put("peers", nodeNames);
        return myMap;
    }

    @GetMapping(value = "/notaries", produces = TEXT_PLAIN_VALUE)
    private String notaries() {
        return proxy.notaryIdentities().toString();
    }

    @PostMapping(value = "/transfer", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> transfer(@RequestParam(value = "receiver") String receiverName,
                                          @RequestParam(value = "amount") long amount,
                                          @RequestParam(value = "currency") String currency,
                                          @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId,
                                          @RequestParam(value = "targetWalletId", required = false) String targetWalletId) {
        try {
            Party receiver = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(receiverName));
            if (receiver == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unknown receiver: " + receiverName);
            }
            
            Amount<Currency> transferAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if ((sourceWalletId != null && !sourceWalletId.trim().isEmpty()) || 
                (targetWalletId != null && !targetWalletId.trim().isEmpty())) {
                UniqueIdentifier sourceId = sourceWalletId != null && !sourceWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(sourceWalletId) : null;
                UniqueIdentifier targetId = targetWalletId != null && !targetWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(targetWalletId) : null;
                result = proxy.startTrackedFlowDynamic(wRDTransferFlow.class, receiver, transferAmount, sourceId, targetId)
                        .getReturnValue().get();
            } else {
                result = proxy.startTrackedFlowDynamic(wRDTransferFlow.class, receiver, transferAmount)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("Transfer to " + receiverName + " completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/wrd-to-rrd", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> wrdToRrd(@RequestParam(value = "amount") long amount,
                                          @RequestParam(value = "currency") String currency,
                                          @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId,
                                          @RequestParam(value = "targetWalletId", required = false) String targetWalletId) {
        try {
            Amount<Currency> conversionAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if ((sourceWalletId != null && !sourceWalletId.trim().isEmpty()) || 
                (targetWalletId != null && !targetWalletId.trim().isEmpty())) {
                UniqueIdentifier sourceId = sourceWalletId != null && !sourceWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(sourceWalletId) : null;
                UniqueIdentifier targetId = targetWalletId != null && !targetWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(targetWalletId) : new UniqueIdentifier();
                result = proxy.startTrackedFlowDynamic(wRD2rRDIssuanceInitFlow.class, conversionAmount, sourceId, targetId)
                        .getReturnValue().get();
            } else {
                String peritelId = java.util.UUID.randomUUID().toString();
                result = proxy.startTrackedFlowDynamic(wRD2rRDIssuanceInitFlow.class, conversionAmount, peritelId)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("wRD to rRD conversion completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/rrd-issuance", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> rrdIssuance(@RequestParam(value = "amount") long amount,
                                             @RequestParam(value = "currency") String currency,
                                             @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId,
                                             @RequestParam(value = "targetWalletId", required = false) String targetWalletId) {
        try {
            Amount<Currency> issuanceAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if ((sourceWalletId != null && !sourceWalletId.trim().isEmpty()) || 
                (targetWalletId != null && !targetWalletId.trim().isEmpty())) {
                UniqueIdentifier sourceId = sourceWalletId != null && !sourceWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(sourceWalletId) : null;
                UniqueIdentifier targetId = targetWalletId != null && !targetWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(targetWalletId) : new UniqueIdentifier();
                result = proxy.startTrackedFlowDynamic(rRDIssuanceInitFlow.class, issuanceAmount, sourceId, targetId)
                        .getReturnValue().get();
            } else {
                String retailId = java.util.UUID.randomUUID().toString();
                result = proxy.startTrackedFlowDynamic(rRDIssuanceInitFlow.class, issuanceAmount, retailId)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("rRD issuance completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @GetMapping(value = "/all-wallets", produces = APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getAllWallets() {
        try {
            QueryCriteria queryCriteria = new QueryCriteria.VaultQueryCriteria(Vault.StateStatus.UNCONSUMED);
            
            // Get wRD wallets
            List<StateAndRef<wRDAccountState>> wrdStates = proxy.vaultQuery(wRDAccountState.class)
                    .getStates()
                    .stream()
                    .filter(stateAndRef -> {
                        wRDAccountState state = stateAndRef.getState().getData();
                        return state.getOwner().getName().equals(me);
                    })
                    .collect(Collectors.toList());

            List<Map<String, Object>> wrdWallets = wrdStates.stream()
                    .map(stateAndRef -> {
                        wRDAccountState state = stateAndRef.getState().getData();
                        Map<String, Object> wallet = new HashMap<>();
                        wallet.put("walletId", state.getWalletId().toString());
                        wallet.put("type", "wRD");
                        wallet.put("owner", state.getOwner().getName().toString());
                        wallet.put("issuer", state.getIssuer().getName().toString());
                        wallet.put("amount", state.getTokenBalance().getQuantity());
                        wallet.put("currency", state.getTokenBalance().getToken().getCurrencyCode());
                        wallet.put("tokenType", state.getTokenType());
                        wallet.put("stateRef", stateAndRef.getRef().toString());
                        return wallet;
                    })
                    .collect(Collectors.toList());

            // Get rRD wallets
            List<StateAndRef<rRDAccountState>> rrdStates = proxy.vaultQuery(rRDAccountState.class)
                    .getStates()
                    .stream()
                    .filter(stateAndRef -> {
                        rRDAccountState state = stateAndRef.getState().getData();
                        return state.getWholesalerParty().getName().equals(me);
                    })
                    .collect(Collectors.toList());

            List<Map<String, Object>> rrdWallets = rrdStates.stream()
                    .map(stateAndRef -> {
                        rRDAccountState state = stateAndRef.getState().getData();
                        Map<String, Object> wallet = new HashMap<>();
                        wallet.put("walletId", state.getWalletId().toString());
                        wallet.put("type", "rRD");
                        wallet.put("owner", state.getOwner());
                        wallet.put("issuer", state.getIssuer());
                        wallet.put("amount", state.getTokenBalance().getQuantity());
                        wallet.put("currency", state.getTokenBalance().getToken().getCurrencyCode());
                        wallet.put("tokenType", state.getTokenType());
                        wallet.put("wholesalerParty", state.getWholesalerParty().getName().toString());
                        wallet.put("isPeritel", state.isPeritel());
                        wallet.put("isRetail", state.isRetail());
                        wallet.put("stateRef", stateAndRef.getRef().toString());
                        return wallet;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> response = new HashMap<>();
            response.put("wrdWallets", wrdWallets);
            response.put("rrdWallets", rrdWallets);
            response.put("totalWrdWallets", wrdWallets.size());
            response.put("totalRrdWallets", rrdWallets.size());
            response.put("totalWallets", wrdWallets.size() + rrdWallets.size());

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping(value = "/redemption", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> redemption(@RequestParam(value = "kdr") String kdrName,
                                            @RequestParam(value = "amount") long amount,
                                            @RequestParam(value = "currency") String currency,
                                            @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId,
                                            @RequestParam(value = "targetWalletId", required = false) String targetWalletId) {
        try {
            Party kdr = proxy.wellKnownPartyFromX500Name(CordaX500Name.parse(kdrName));
            if (kdr == null) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body("Unknown KDR: " + kdrName);
            }
            
            Amount<Currency> redemptionAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if ((sourceWalletId != null && !sourceWalletId.trim().isEmpty()) || 
                (targetWalletId != null && !targetWalletId.trim().isEmpty())) {
                UniqueIdentifier sourceId = sourceWalletId != null && !sourceWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(sourceWalletId) : null;
                UniqueIdentifier targetId = targetWalletId != null && !targetWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(targetWalletId) : null;
                result = proxy.startTrackedFlowDynamic(wRDRedemptionFlow.class, kdr, redemptionAmount, sourceId, targetId)
                        .getReturnValue().get();
            } else {
                result = proxy.startTrackedFlowDynamic(wRDRedemptionFlow.class, kdr, redemptionAmount)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("Redemption to " + kdrName + " completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }

    @PostMapping(value = "/rrd-to-wrd", produces = TEXT_PLAIN_VALUE)
    public ResponseEntity<String> rrdToWrd(@RequestParam(value = "amount") long amount,
                                          @RequestParam(value = "currency") String currency,
                                          @RequestParam(value = "sourceWalletId", required = false) String sourceWalletId,
                                          @RequestParam(value = "targetWalletId", required = false) String targetWalletId) {
        try {
            Amount<Currency> conversionAmount = new Amount<>(amount, Currency.getInstance(currency));
            SignedTransaction result;
            
            if ((sourceWalletId != null && !sourceWalletId.trim().isEmpty()) || 
                (targetWalletId != null && !targetWalletId.trim().isEmpty())) {
                UniqueIdentifier sourceId = sourceWalletId != null && !sourceWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(sourceWalletId) : null;
                UniqueIdentifier targetId = targetWalletId != null && !targetWalletId.trim().isEmpty() ? 
                    UniqueIdentifier.Companion.fromString(targetWalletId) : null;
                result = proxy.startTrackedFlowDynamic(rRD2wRDRedemptionFlow.class, conversionAmount, sourceId, targetId)
                        .getReturnValue().get();
            } else {
                String peritelOwner = "default-peritel-owner";
                result = proxy.startTrackedFlowDynamic(rRD2wRDRedemptionFlow.class, conversionAmount, peritelOwner)
                        .getReturnValue().get();
            }
            
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body("rRD to wRD conversion completed. Transaction ID: " + result.getId());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Error: " + e.getMessage());
        }
    }
}