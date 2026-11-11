package com.futureplus.client;

import net.fabricmc.api.ClientModInitializer;

public class FutureplusClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		PistonAura.init();
		MaceAura.init();
		FakeFly.init();
		Blocker.init();
		AnchorAura.init();
		Suicide.init();
		AntiPhase.init();
		FakePlayerPlus.init();
		KillEffects.init();
		ClickGui.register();
	}
}
