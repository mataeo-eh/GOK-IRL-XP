package com.gokirlbankedxp.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gokirlbankedxp.GokIrlBankedXpPlugin;
import com.gokirlbankedxp.model.IrlAction;
import com.gokirlbankedxp.service.ActionLogManager;
import com.gokirlbankedxp.service.IrlActionManager;
import com.gokirlbankedxp.service.TestMultipliers;
import com.gokirlbankedxp.service.TimerManager;
import com.google.gson.Gson;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import org.junit.jupiter.api.Test;

/** Verifies how the Actions tab splits timed actions from logged-only ones. */
class IrlActionsPanelTest
{
    /**
     * The timer drop-down must offer only timed actions, while the log drop-down
     * offers everything. Starting a timer on an untimed action could never award
     * anything, and logging units is valid for either kind.
     */
    @Test
    void timerSelectorListsOnlyTimedActionsWhileLogSelectorListsAll() throws Exception
    {
        ConfigManager configManager = inMemoryConfigManager(new HashMap<>());
        IrlActionManager actionManager = new IrlActionManager(configManager, new Gson());
        actionManager.loadActions();

        actionManager.createAction(IrlAction.builder()
            .name("Running")
            .unitName("km")
            .timed(Boolean.FALSE)
            .defaultXpPerUnit(250L)
            .skillMappings(Map.of(Skill.AGILITY, 250L))
            .build());
        actionManager.createAction(IrlAction.builder()
            .name("Reading")
            .unitName("Minutes")
            .secondsPerUnit(60L)
            .timed(Boolean.TRUE)
            .defaultXpPerUnit(10L)
            .skillMappings(Map.of(Skill.MAGIC, 10L))
            .build());

        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        TimerManager timerManager =
            new TimerManager(configManager, actionManager, plugin, null, new Gson());
        ActionLogManager logManager = new ActionLogManager(actionManager, plugin, new TestMultipliers().multiplierManager);

        AtomicReference<IrlActionsPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() ->
            panelReference.set(new IrlActionsPanel(actionManager, timerManager, logManager)));

        // The panel populates its models through invokeLater, so let the queued
        // refresh run before inspecting them.
        SwingUtilities.invokeAndWait(() -> { });

        AtomicReference<List<JComboBox<?>>> combosReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> combosReference.set(findComboBoxes(panelReference.get())));

        List<JComboBox<?>> combos = combosReference.get();
        // Declaration order in the panel: the log card is built before the timer
        // card, so the log selector is the first combo in the component tree.
        assertEquals(2, combos.size(), "expected exactly the log and timer selectors");
        assertEquals(List.of("Running", "Reading"), namesOf(combos.get(0)));
        assertEquals(List.of("Reading"), namesOf(combos.get(1)));
    }

    @Test
    void panelBuildsWithAnEmptyActionLibrary() throws Exception
    {
        ConfigManager configManager = inMemoryConfigManager(new HashMap<>());
        IrlActionManager actionManager = new IrlActionManager(configManager, new Gson());
        actionManager.loadActions();

        GokIrlBankedXpPlugin plugin = mock(GokIrlBankedXpPlugin.class);
        TimerManager timerManager =
            new TimerManager(configManager, actionManager, plugin, null, new Gson());

        AtomicReference<IrlActionsPanel> panelReference = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> panelReference.set(new IrlActionsPanel(
            actionManager, timerManager, new ActionLogManager(actionManager, plugin, new TestMultipliers().multiplierManager))));
        SwingUtilities.invokeAndWait(() -> { });

        assertNotNull(panelReference.get());
    }

    private static List<String> namesOf(JComboBox<?> combo)
    {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++)
        {
            names.add(((IrlAction) combo.getItemAt(i)).getName());
        }
        return names;
    }

    private static List<JComboBox<?>> findComboBoxes(Container container)
    {
        List<JComboBox<?>> found = new ArrayList<>();
        for (Component child : container.getComponents())
        {
            if (child instanceof JComboBox)
            {
                found.add((JComboBox<?>) child);
            }
            else if (child instanceof Container)
            {
                found.addAll(findComboBoxes((Container) child));
            }
        }
        return found;
    }

    private static ConfigManager inMemoryConfigManager(Map<String, String> values)
    {
        ConfigManager configManager = mock(ConfigManager.class);
        when(configManager.getConfiguration(anyString(), anyString())).thenAnswer(invocation ->
            values.get(invocation.getArgument(0) + "." + invocation.getArgument(1)));
        doAnswer(invocation -> {
            String key = invocation.getArgument(0) + "." + invocation.getArgument(1);
            Object value = invocation.getArgument(2);
            values.put(key, String.valueOf(value));
            return null;
        }).when(configManager).setConfiguration(anyString(), anyString(), any());
        return configManager;
    }
}
