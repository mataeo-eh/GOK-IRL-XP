package com.gokirlbankedxp;

import com.gokirlbankedxp.ui.IrlActionsPanel;
import com.gokirlbankedxp.ui.IrlXpUi;
import com.gokirlbankedxp.ui.XpMultipliersPanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.PluginPanel;

/**
 * The single sidebar home for every part of IRL XP.
 *
 * <p>The child panels keep their focused responsibilities, while this shell
 * owns navigation and presentation. That lets XP, action and multiplier
 * behavior evolve independently without exposing them as separate RuneLite
 * plugins.</p>
 *
 * <p>Every one of the plugin's tabs is a button in the strip below the title,
 * and the line under the title always says what the visible tab is for. Together
 * they are the answer to "where do I change this?" — nothing the user can edit
 * lives somewhere they have to already know about, apart from the warning
 * settings, which RuneLite requires to be in its own settings panel and which
 * the banked XP tab points at explicitly.</p>
 */
@Singleton
class GokIrlXpPanel extends PluginPanel
{
    private static final String XP_CARD = "XP";
    private static final String ACTIONS_CARD = "ACTIONS";
    private static final String MULTIPLIERS_CARD = "MULTIPLIERS";

    private final GokIrlBankedXpPanel bankedXpPanel;
    private final IrlActionsPanel actionsPanel;
    private final XpMultipliersPanel multipliersPanel;
    private final CardLayout contentLayout = new CardLayout();
    private final JPanel content = new JPanel(contentLayout);
    private final JLabel sectionDescription = IrlXpUi.mutedLabel("Bank real-world XP.");
    private final JButton xpButton = new JButton("BANKED XP");
    private final JButton actionsButton = new JButton("ACTIONS");
    private final JButton multipliersButton = new JButton("LEVEL MULTIPLIERS");

    @Inject
    GokIrlXpPanel(
        GokIrlBankedXpPanel bankedXpPanel,
        IrlActionsPanel actionsPanel,
        XpMultipliersPanel multipliersPanel)
    {
        this.bankedXpPanel = bankedXpPanel;
        this.actionsPanel = actionsPanel;
        this.multipliersPanel = multipliersPanel;

        setLayout(new BorderLayout());
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        add(buildHeader(), BorderLayout.NORTH);

        content.setBackground(IrlXpUi.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(bankedXpPanel, XP_CARD);
        content.add(actionsPanel, ACTIONS_CARD);
        content.add(multipliersPanel, MULTIPLIERS_CARD);
        add(content, BorderLayout.CENTER);

        showSection(XP_CARD);
    }

    private JPanel buildHeader()
    {
        JPanel header = new JPanel();
        header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
        header.setBackground(new Color(0x111316));
        header.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, IrlXpUi.BORDER),
            BorderFactory.createEmptyBorder(14, 12, 10, 12)));

        JPanel identity = new JPanel(new BorderLayout(10, 0));
        identity.setOpaque(false);
        JLabel mark = new JLabel("XP", JLabel.CENTER);
        mark.setOpaque(true);
        mark.setBackground(IrlXpUi.ACCENT);
        mark.setForeground(new Color(0x18130B));
        mark.setFont(mark.getFont().deriveFont(Font.BOLD, 13f));
        mark.setPreferredSize(new Dimension(34, 34));
        identity.add(mark, BorderLayout.WEST);

        JPanel titles = new JPanel(new GridLayout(0, 1, 0, 1));
        titles.setOpaque(false);
        JLabel title = new JLabel("IRL XP");
        title.setForeground(IrlXpUi.TEXT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        titles.add(title);
        titles.add(sectionDescription);
        identity.add(titles, BorderLayout.CENTER);
        header.add(identity);
        header.add(Box.createVerticalStrut(12));

        // Two rows rather than three side-by-side buttons: the sidebar is about
        // 225px wide, and a third of that cannot hold a word as long as
        // "MULTIPLIERS" without truncating it to something unreadable. Giving the
        // multipliers tab its own full-width row keeps every tab spelled out.
        JPanel tabs = new JPanel(new GridLayout(2, 1, 0, 5));
        tabs.setOpaque(false);

        JPanel topRow = new JPanel(new GridLayout(1, 2, 5, 0));
        topRow.setOpaque(false);
        configureTab(xpButton, XP_CARD);
        configureTab(actionsButton, ACTIONS_CARD);
        topRow.add(xpButton);
        topRow.add(actionsButton);
        tabs.add(topRow);

        configureTab(multipliersButton, MULTIPLIERS_CARD);
        multipliersButton.setToolTipText(
            "Bank more (or less) XP as your in-game levels rise. Set per skill.");
        tabs.add(multipliersButton);

        header.add(tabs);
        return header;
    }

    private void configureTab(JButton button, String card)
    {
        button.setFont(button.getFont().deriveFont(Font.BOLD, 10f));
        button.setFocusPainted(false);
        button.setBorder(BorderFactory.createEmptyBorder(7, 5, 7, 5));
        button.addActionListener(event -> showSection(card));
    }

    private void showSection(String card)
    {
        contentLayout.show(content, card);

        // The description under the title always names what the visible tab does,
        // so the sidebar explains itself without the user pressing anything.
        String description;
        if (ACTIONS_CARD.equals(card))
        {
            description = "Manage actions and timers.";
        }
        else if (MULTIPLIERS_CARD.equals(card))
        {
            description = "Earn more XP as you level.";
        }
        else
        {
            description = "Bank real-world XP.";
        }
        sectionDescription.setText(description);

        styleTab(xpButton, XP_CARD.equals(card));
        styleTab(actionsButton, ACTIONS_CARD.equals(card));
        styleTab(multipliersButton, MULTIPLIERS_CARD.equals(card));

        if (MULTIPLIERS_CARD.equals(card))
        {
            // Levels move while this tab is hidden, so what it shows is stale by
            // the time it is opened again.
            multipliersPanel.refresh();
        }
    }

    private void styleTab(JButton button, boolean selected)
    {
        button.setOpaque(true);
        button.setBackground(selected ? IrlXpUi.ACCENT : IrlXpUi.SURFACE_RAISED);
        button.setForeground(selected ? new Color(0x18130B) : IrlXpUi.MUTED_TEXT);
    }

    void updateSnapshot(GokIrlBankedXpPlugin.BankedXpSnapshot snapshot)
    {
        bankedXpPanel.updateSnapshot(snapshot);
        // A snapshot is published on every XP change, and an XP change can be a
        // level-up that silently alters what the next deposit is worth. The
        // multipliers tab is where that is visible, so it is refreshed too.
        multipliersPanel.refresh();
    }

    void refreshActions()
    {
        actionsPanel.refreshActionList();
        actionsPanel.startUiUpdates();
    }

    void stopUiUpdates()
    {
        actionsPanel.stopUiUpdates();
    }
}
