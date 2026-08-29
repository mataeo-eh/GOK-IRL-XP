package com.gokirlbankedxp;

import com.gokirlbankedxp.ui.IrlActionsPanel;
import com.gokirlbankedxp.ui.IrlXpUi;
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
 * The single sidebar home for both halves of IRL XP.
 *
 * <p>The child panels keep their focused responsibilities, while this shell
 * owns navigation and presentation. That lets XP and action behavior evolve
 * independently without exposing them as separate RuneLite plugins.</p>
 */
@Singleton
class GokIrlXpPanel extends PluginPanel
{
    private static final String XP_CARD = "XP";
    private static final String ACTIONS_CARD = "ACTIONS";

    private final GokIrlBankedXpPanel bankedXpPanel;
    private final IrlActionsPanel actionsPanel;
    private final CardLayout contentLayout = new CardLayout();
    private final JPanel content = new JPanel(contentLayout);
    private final JLabel sectionDescription = IrlXpUi.mutedLabel("Bank real-world XP.");
    private final JButton xpButton = new JButton("BANKED XP");
    private final JButton actionsButton = new JButton("ACTIONS");

    @Inject
    GokIrlXpPanel(GokIrlBankedXpPanel bankedXpPanel, IrlActionsPanel actionsPanel)
    {
        this.bankedXpPanel = bankedXpPanel;
        this.actionsPanel = actionsPanel;

        setLayout(new BorderLayout());
        setBackground(IrlXpUi.BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

        add(buildHeader(), BorderLayout.NORTH);

        content.setBackground(IrlXpUi.BACKGROUND);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        content.add(bankedXpPanel, XP_CARD);
        content.add(actionsPanel, ACTIONS_CARD);
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

        JPanel tabs = new JPanel(new GridLayout(1, 2, 5, 0));
        tabs.setOpaque(false);
        configureTab(xpButton, XP_CARD);
        configureTab(actionsButton, ACTIONS_CARD);
        tabs.add(xpButton);
        tabs.add(actionsButton);
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
        boolean showingXp = XP_CARD.equals(card);
        contentLayout.show(content, card);
        sectionDescription.setText(showingXp
            ? "Bank real-world XP."
            : "Manage actions and timers.");
        styleTab(xpButton, showingXp);
        styleTab(actionsButton, !showingXp);
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
