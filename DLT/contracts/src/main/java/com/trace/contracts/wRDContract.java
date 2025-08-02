package com.trace.contracts;

import com.trace.states.wRDAccountState;
import net.corda.core.contracts.*;
import net.corda.core.identity.Party;
import net.corda.core.transactions.LedgerTransaction;
import org.jetbrains.annotations.NotNull;

import java.time.Instant;

import static net.corda.core.contracts.ContractsDSL.requireThat;

public class wRDContract implements Contract {
    public static final String ID = "com.trace.contracts.wRDContract";

    @Override
    public void verify(@NotNull LedgerTransaction tx) {
        if (tx.getCommands().isEmpty()) {
            throw new IllegalArgumentException("No commands found");
        }
        final CommandWithParties<CommandData> command = tx.getCommands().get(0);
        final CommandData commandData = command.getValue();

        if (commandData instanceof Commands.wRDCentralInitCommand) {
            verifyWRDCentralInit(tx);
        } else {
            throw new IllegalArgumentException("Unrecognised command");
        }
    }

    private void verifyWRDCentralInit(LedgerTransaction tx) {
        requireThat(require -> {
            require.using("No input states should be consumed", tx.getInputs().isEmpty());
            require.using("Only one output state should be created", tx.getOutputs().size() == 1);

            final wRDAccountState output = tx.outputsOfType(wRDAccountState.class).get(0);

            require.using("Owner and issuer must be the same (KDR)", output.getOwner().equals(output.getIssuer()));
            require.using("Owner must be KDR", isKDR(output.getOwner()));
            require.using("Token balance must be greater than 0", output.getTokenBalance().getQuantity() > 0);
            require.using("Token type must be IDR", output.getTokenType().equals("IDR"));
            require.using("Version must be greater than 0", output.getVersion() > 0);
            require.using("Last Modified must be before now", output.getLastModified().isBefore(Instant.now()));

            return null;
        });
    }

    private boolean isKDR(Party party) {
        return party.getName().getOrganisation().equals("KDR");
    }

    public interface Commands extends CommandData {
        class wRDCentralInitCommand implements Commands {}
    }
}