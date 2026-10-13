package com.wildermods.thrixlvault.exception;

import com.wildermods.thrixlvault.Vault;

public class DatabaseVaultCircularityError extends DatabaseError {

	public DatabaseVaultCircularityError(Vault vault, String... names) {
		super("Vault: " + vault.vaultDir + "\n\nCircularity: " + String.join("->", names));
	}
	
}
