package com.trace.flows;

import co.paralleluniverse.fibers.Suspendable;
import com.trace.states.rRDAccountState;
import net.corda.core.contracts.StateAndRef;
import net.corda.core.flows.*;
import net.corda.core.node.ServiceHub;
import net.corda.core.node.services.Vault.StateStatus;
import net.corda.core.node.services.vault.QueryCriteria;
import com.trace.states.wRDAccountState;

import java.util.List;

public class DevUtilFlow {
    @InitiatingFlow
    @StartableByRPC
    public static class QueryConsumedWRDAccountStateFlow extends FlowLogic<List<StateAndRef<wRDAccountState>>> {

        @Suspendable
        @Override
        public List<StateAndRef<wRDAccountState>> call() throws FlowException {
            ServiceHub serviceHub = getServiceHub();

            QueryCriteria criteria = new QueryCriteria.VaultQueryCriteria(StateStatus.CONSUMED);
            return serviceHub.getVaultService().queryBy(wRDAccountState.class, criteria).getStates();
        }
    }

    @InitiatingFlow
    @StartableByRPC
    public static class QueryConsumedRRDAccountStateFlow extends FlowLogic<List<StateAndRef<rRDAccountState>>> {

        @Suspendable
        @Override
        public List<StateAndRef<rRDAccountState>> call() throws FlowException {
            ServiceHub serviceHub = getServiceHub();

            QueryCriteria criteria = new QueryCriteria.VaultQueryCriteria(StateStatus.CONSUMED);
            return serviceHub.getVaultService().queryBy(rRDAccountState.class, criteria).getStates();
        }
    }
}