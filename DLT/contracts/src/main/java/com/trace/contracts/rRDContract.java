package com.trace.contracts;

import com.trace.states.rRDAccountState;
import com.trace.states.wRDAccountState;
import net.corda.core.contracts.*;
import net.corda.core.transactions.LedgerTransaction;

import java.util.Currency;
import java.util.List;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class rRDContract implements Contract {
    public static final String ID = "com.trace.contracts.rRDContract";

    @Override
    public void verify(LedgerTransaction tx) {
        if (tx.getCommands().isEmpty()) {
            throw new IllegalArgumentException("No commands found");
        }
        final CommandWithParties<CommandData> command = tx.getCommands().get(0);
        final CommandData commandData = command.getValue();

        if (commandData instanceof Commands.wRD2rRDIssuanceInitCommand) {
            verifyWRD2rRDIssuanceInit(tx);
        } else if (commandData instanceof Commands.wRD2rRDIssuanceCommand) {
            verifyWRD2rRDIssuance(tx);
        } else if (commandData instanceof Commands.rRD2wRDRedemptionCommand) {
            verifyRRD2wRDRedemption(tx);
        } else if (commandData instanceof Commands.rRDIssuanceInitCommand) {
            verifyRRDIssuanceInit(tx);
        } else if (commandData instanceof Commands.rRDIssuanceCommand) {
            verifyRRDIssuance(tx);
        } else if (commandData instanceof Commands.rRDRedemptionCommand) {
            verifyRRDRedemption(tx);
        } else if (commandData instanceof Commands.rRDTransferCommand) {
            verifyRRDTransfer(tx);
        } else {
            throw new IllegalArgumentException("Unrecognised command");
        }
    }

    private void verifyWRD2rRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("One input state should be consumed", tx.getInputs().size() == 1);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final wRDAccountState wRDInput = tx.inputsOfType(wRDAccountState.class).get(0);
            final wRDAccountState wRDOutput = tx.outputsOfType(wRDAccountState.class).get(0);
            final rRDAccountState rRDOutput = tx.outputsOfType(rRDAccountState.class).get(0);
            
            require.using("Sender's owner must be Wholesaler", !isKDR(wRDInput.getOwner()));
            require.using("Receipt's owner must be Peritel", rRDOutput.isPeritel());
            require.using("Wholesaler's Peritel must be same with sender's owner", 
                rRDOutput.getWholesalerFromOwner().equals(wRDInput.getOwner().getName().getOrganisation()));
            
            Amount<Currency> transferAmount = wRDInput.getTokenBalance().minus(wRDOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", wRDInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", wRDInput.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private void verifyWRD2rRDIssuance(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("Two input states should be consumed", tx.getInputs().size() == 2);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final wRDAccountState wRDInput = tx.inputsOfType(wRDAccountState.class).get(0);
            final rRDAccountState rRDInput = tx.inputsOfType(rRDAccountState.class).get(0);
            final wRDAccountState wRDOutput = tx.outputsOfType(wRDAccountState.class).get(0);
            final rRDAccountState rRDOutput = tx.outputsOfType(rRDAccountState.class).get(0);
            
            require.using("Sender's owner must be Wholesaler", !isKDR(wRDInput.getOwner()));
            require.using("Receipt's owner must be Peritel", rRDInput.isPeritel());
            require.using("Wholesaler's Peritel must be same with sender's owner", 
                rRDInput.getWholesalerFromOwner().equals(wRDInput.getOwner().getName().getOrganisation()));
            
            Amount<Currency> transferAmount = wRDInput.getTokenBalance().minus(wRDOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", wRDInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", wRDInput.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private void verifyRRD2wRDRedemption(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("Two input states should be consumed", tx.getInputs().size() == 2);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final rRDAccountState rRDInput = tx.inputsOfType(rRDAccountState.class).get(0);
            final wRDAccountState wRDInput = tx.inputsOfType(wRDAccountState.class).get(0);
            final rRDAccountState rRDOutput = tx.outputsOfType(rRDAccountState.class).get(0);
            final wRDAccountState wRDOutput = tx.outputsOfType(wRDAccountState.class).get(0);
            
            require.using("Sender's owner must be Peritel", rRDInput.isPeritel());
            require.using("Receipt's owner must be Wholesaler", !isKDR(wRDInput.getOwner()));
            require.using("Wholesaler's Peritel must be same with receipt's owner", 
                rRDInput.getWholesalerFromOwner().equals(wRDInput.getOwner().getName().getOrganisation()));
            
            Amount<Currency> transferAmount = rRDInput.getTokenBalance().minus(rRDOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", rRDInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", rRDInput.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private void verifyRRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("One input state should be consumed", tx.getInputs().size() == 1);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final rRDAccountState input = tx.inputsOfType(rRDAccountState.class).get(0);
            final List<rRDAccountState> outputs = tx.outputsOfType(rRDAccountState.class);
            
            require.using("Sender's owner must be Peritel", input.isPeritel());
            
            rRDAccountState peritelOutput = null;
            rRDAccountState retailOutput = null;
            
            for (rRDAccountState output : outputs) {
                if (output.isPeritel()) {
                    peritelOutput = output;
                } else if (output.isRetail()) {
                    retailOutput = output;
                }
            }
            
            require.using("Must have Peritel output", peritelOutput != null);
            require.using("Must have Retail output", retailOutput != null);
            require.using("Receipt's owner must be Retail", retailOutput.isRetail());
            require.using("Wholesaler's Peritel must be same with Wholesaler's Retail", 
                input.getWholesalerFromOwner().equals(retailOutput.getWholesalerFromOwner()));
            
            Amount<Currency> transferAmount = input.getTokenBalance().minus(peritelOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", input.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", input.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private void verifyRRDIssuance(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("Two input states should be consumed", tx.getInputs().size() == 2);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final List<rRDAccountState> inputs = tx.inputsOfType(rRDAccountState.class);
            final List<rRDAccountState> outputs = tx.outputsOfType(rRDAccountState.class);
            
            rRDAccountState peritelInput = null;
            rRDAccountState retailInput = null;
            
            for (rRDAccountState input : inputs) {
                if (input.isPeritel()) {
                    peritelInput = input;
                } else if (input.isRetail()) {
                    retailInput = input;
                }
            }
            
            require.using("Must have Peritel input", peritelInput != null);
            require.using("Must have Retail input", retailInput != null);
            require.using("Sender's owner must be Peritel", peritelInput.isPeritel());
            require.using("Receipt's owner must be Retail", retailInput.isRetail());
            require.using("Wholesaler's Peritel must be same with Wholesaler's Retail", 
                peritelInput.getWholesalerFromOwner().equals(retailInput.getWholesalerFromOwner()));
            
            rRDAccountState peritelOutput = outputs.stream().filter(o -> o.isPeritel()).findFirst().get();
            Amount<Currency> transferAmount = peritelInput.getTokenBalance().minus(peritelOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", peritelInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", peritelInput.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private void verifyRRDRedemption(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("Two input states should be consumed", tx.getInputs().size() == 2);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final List<rRDAccountState> inputs = tx.inputsOfType(rRDAccountState.class);
            final List<rRDAccountState> outputs = tx.outputsOfType(rRDAccountState.class);
            
            rRDAccountState retailInput = null;
            rRDAccountState peritelInput = null;
            
            for (rRDAccountState input : inputs) {
                if (input.isRetail()) {
                    retailInput = input;
                } else if (input.isPeritel()) {
                    peritelInput = input;
                }
            }
            
            require.using("Must have Retail input", retailInput != null);
            require.using("Must have Peritel input", peritelInput != null);
            require.using("Sender's owner must be Retail", retailInput.isRetail());
            require.using("Receipt's owner must be Peritel", peritelInput.isPeritel());
            require.using("Wholesaler's Peritel must be same with Wholesaler's Retail", 
                retailInput.getWholesalerFromOwner().equals(peritelInput.getWholesalerFromOwner()));
            
            rRDAccountState retailOutput = outputs.stream().filter(o -> o.isRetail()).findFirst().get();
            Amount<Currency> transferAmount = retailInput.getTokenBalance().minus(retailOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", retailInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", retailInput.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private void verifyRRDTransfer(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("Two input states should be consumed", tx.getInputs().size() == 2);
            require.using("Two output states should be created", tx.getOutputs().size() == 2);
            
            final List<rRDAccountState> inputs = tx.inputsOfType(rRDAccountState.class);
            final List<rRDAccountState> outputs = tx.outputsOfType(rRDAccountState.class);
            
            rRDAccountState senderInput = inputs.get(0);
            rRDAccountState receiverInput = inputs.get(1);
            
            require.using("Sender's owner must be Retail", senderInput.isRetail());
            require.using("Receipt's owner must be Retail", receiverInput.isRetail());
            require.using("Sender and receiver must be different", !senderInput.getOwner().equals(receiverInput.getOwner()));
            
            rRDAccountState senderOutput = outputs.stream().filter(o -> o.getOwner().equals(senderInput.getOwner())).findFirst().get();
            Amount<Currency> transferAmount = senderInput.getTokenBalance().minus(senderOutput.getTokenBalance());
            require.using("Transfer amount must be greater than 0", transferAmount.getQuantity() > 0);
            require.using("Sender must have sufficient balance", senderInput.getTokenBalance().getQuantity() >= transferAmount.getQuantity());
            require.using("Token type must be IDR", senderInput.getTokenType().equals("IDR"));
            
            return null;
        });
    }

    private boolean isKDR(net.corda.core.identity.Party party) {
        return party.getName().getOrganisation().equals("KDR");
    }

    public interface Commands extends CommandData {
        class wRD2rRDIssuanceInitCommand implements Commands {}
        class wRD2rRDIssuanceCommand implements Commands {}
        class rRD2wRDRedemptionCommand implements Commands {}
        class rRDIssuanceInitCommand implements Commands {}
        class rRDIssuanceCommand implements Commands {}
        class rRDRedemptionCommand implements Commands {}
        class rRDTransferCommand implements Commands {}
    }
}