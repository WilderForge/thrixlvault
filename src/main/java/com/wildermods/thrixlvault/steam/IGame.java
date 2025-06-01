package com.wildermods.thrixlvault.steam;

@FunctionalInterface
public interface IGame {

	public long game();
	
	public default String gameName() {
		return game() + "";
	}
	
}
