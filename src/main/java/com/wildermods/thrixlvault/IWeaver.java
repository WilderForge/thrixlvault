package com.wildermods.thrixlvault;

import java.io.IOException;
import java.util.concurrent.ExecutionException;

import com.wildermods.masshash.exception.IntegrityException;
import com.wildermods.thrixlvault.steam.IVaultable;

public interface IWeaver extends IVaultable {

	public void verify() throws IllegalStateException, IntegrityException, IOException, InterruptedException, ExecutionException;
	
	public ChrysalisizedVault weave() throws IllegalStateException, IOException, IntegrityException;

}
