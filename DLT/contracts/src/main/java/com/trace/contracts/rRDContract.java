package com.trace.contracts;

import net.corda.core.contracts.*;
import net.corda.core.transactions.LedgerTransaction;

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
        } else {
            throw new IllegalArgumentException("Unrecognised command");
        }
    }

    private void verifyWRD2rRDIssuanceInit(LedgerTransaction tx) {
        requireThat(require -> {
            return null;
        });
    }

    public interface Commands extends CommandData {
        class wRD2rRDIssuanceInitCommand implements Commands {}
    }
}