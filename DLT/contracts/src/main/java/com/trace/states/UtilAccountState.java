package com.trace.states;

import net.corda.core.contracts.StateAndRef;
import net.corda.core.contracts.UniqueIdentifier;
import net.corda.core.serialization.CordaSerializable;

public class UtilAccountState {
    @CordaSerializable
    public static class StateSyncRequest {
        private final UniqueIdentifier sourceWalletId;
        private final UniqueIdentifier receiverWalletId;

        public StateSyncRequest(UniqueIdentifier sourceWalletId, UniqueIdentifier receiverWalletId) {
            this.sourceWalletId = sourceWalletId;
            this.receiverWalletId = receiverWalletId;
        }

        public UniqueIdentifier getSourceWalletId() {
            return sourceWalletId;
        }

        public UniqueIdentifier getReceiverWalletId() {
            return receiverWalletId;
        }
    }

    @CordaSerializable
    public static class StateSyncResponse {
        private final StateAndRef<wRDAccountState> stateAndRefOfSourceWalletState;
        private final StateAndRef<wRDAccountState> stateAndRefOfReceiverWalletState;

        public StateSyncResponse(StateAndRef<wRDAccountState> stateAndRefOfSourceWalletState,
                                 StateAndRef<wRDAccountState> stateAndRefOfReceiverWalletState) {
            if (stateAndRefOfReceiverWalletState == null) {
                // The stateAndRef of Receiver Wallet State from responder (receiver party) must be not null.
                throw new IllegalArgumentException("StateAndRef of receiver wallet state from responder (receiver party) " +
                        "cannot be null.");
            }
            this.stateAndRefOfSourceWalletState = stateAndRefOfSourceWalletState;
            this.stateAndRefOfReceiverWalletState = stateAndRefOfReceiverWalletState;
        }

        public StateAndRef<wRDAccountState> getStateAndRefOfSourceWalletState() {
            return stateAndRefOfSourceWalletState;
        }

        public StateAndRef<wRDAccountState> getStateAndRefOfReceiverWalletState() {
            return stateAndRefOfReceiverWalletState;
        }
    }

    @CordaSerializable
    public static class StateSyncDetermined {
        private final StateAndRef<wRDAccountState> stateAndRefSourceWalletStateDetermined;
        private final StateAndRef<wRDAccountState> stateAndRefReceiverWalletStateDetermined;

        public StateSyncDetermined(StateAndRef<wRDAccountState> stateAndRefSourceWalletStateDetermined,
                                   StateAndRef<wRDAccountState> stateAndRefReceiverWalletStateDetermined) {
            this.stateAndRefSourceWalletStateDetermined = stateAndRefSourceWalletStateDetermined;
            this.stateAndRefReceiverWalletStateDetermined = stateAndRefReceiverWalletStateDetermined;
        }

        public StateAndRef<wRDAccountState> getStateAndRefSourceWalletStateDetermined() {
            return stateAndRefSourceWalletStateDetermined;
        }

        public StateAndRef<wRDAccountState> getStateAndRefReceiverWalletStateDetermined() {
            return stateAndRefReceiverWalletStateDetermined;
        }
    }
}
