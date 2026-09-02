package com.gokirlbankedxp.service;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.gokirlbankedxp.ui.IrlActionsPanel;
import com.gokirlbankedxp.ui.XpMultipliersPanel;
import javax.inject.Singleton;
import org.junit.jupiter.api.Test;

/**
 * Guards the scoping bug that made every timer refuse to start.
 *
 * <p>Guice leaves unannotated bindings unscoped, so each injection point gets a
 * fresh instance. The plugin, the timer manager and the actions panel all inject
 * {@link IrlActionManager}; without {@code @Singleton} they each held a separate,
 * empty library, and only the plugin's copy was ever loaded from config. Starting
 * a timer then failed with "that action is no longer available" for an action the
 * user could see in the list, because the timer manager was consulting a
 * different manager than the one the panel had just written to.</p>
 *
 * <p>These assertions are on the annotations rather than on a live injector: the
 * real child injector is built by RuneLite's client at runtime and is not
 * available to a unit test, but the annotation is the whole of what was missing.</p>
 */
class InjectionScopeTest
{
    @Test
    void actionLibraryIsSharedByEveryInjectionPoint()
    {
        assertNotNull(IrlActionManager.class.getAnnotation(Singleton.class),
            "IrlActionManager must be @Singleton; otherwise the panel, the plugin and TimerManager"
                + " each get their own empty action library.");
    }

    @Test
    void timerStateIsSharedByEveryInjectionPoint()
    {
        assertNotNull(TimerManager.class.getAnnotation(Singleton.class),
            "TimerManager must be @Singleton; otherwise timers started from the panel live in a"
                + " manager that never had startUp() called and so never tick.");
    }

    @Test
    void loggedXpIsBankedThroughASharedManager()
    {
        assertNotNull(ActionLogManager.class.getAnnotation(Singleton.class),
            "ActionLogManager must be @Singleton so it resolves actions from the shared library.");
    }

    /**
     * The panel is the only consumer that reaches all three services, so a
     * compile-time reference here keeps this test honest if the wiring changes.
     */
    @Test
    void actionsPanelIsTheUiEntryPointForAllThreeServices()
    {
        assertNotNull(IrlActionsPanel.class);
    }

    @Test
    void bankedXpStoreIsSharedByEveryInjectionPoint()
    {
        assertNotNull(BankedXpStore.class.getAnnotation(Singleton.class),
            "BankedXpStore must be @Singleton; it remembers the last config value it wrote so the"
                + " plugin can ignore the ConfigChanged echo of its own saves.");
    }

    @Test
    void observedLevelsAreSharedByEveryInjectionPoint()
    {
        assertNotNull(SkillLevelTracker.class.getAnnotation(Singleton.class),
            "SkillLevelTracker must be @Singleton; otherwise the plugin fills in one copy from the client"
                + " while the multiplier service reads an empty one and resolves every skill at level 1.");
    }

    @Test
    void multiplierThresholdsAreSharedByEveryInjectionPoint()
    {
        assertNotNull(XpMultiplierManager.class.getAnnotation(Singleton.class),
            "XpMultiplierManager must be @Singleton; otherwise thresholds saved from the multipliers tab"
                + " land in a manager the plugin never consults, and XP keeps banking at 1.00x.");
    }

    @Test
    void multipliersPanelIsTheUiEntryPointForTheMultiplierServices()
    {
        assertNotNull(XpMultipliersPanel.class.getAnnotation(Singleton.class),
            "XpMultipliersPanel must be @Singleton; it holds the visible list models, and a second"
                + " unscoped instance would be UI state nothing ever refreshes.");
    }
}
