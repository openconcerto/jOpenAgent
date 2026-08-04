package org.jopenagent.trace.viewer.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.event.TreeSelectionListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;

import org.jopenkit.json.JSON;
import org.jopenkit.json.JSONArray;
import org.jopenkit.json.JSONObject;

import org.jopenagent.trace.JsonTraceExporter;
import org.jopenagent.trace.Span;
import org.jopenagent.trace.TraceExporter;

/**
 * A native desktop trace viewer built entirely on Swing (JDK built-in, zero dependency) \u2014 an
 * alternative to {@link org.jopenagent.trace.viewer.TraceViewerServer}'s browser-based UI for anyone
 * who'd rather not open a browser. Two modes:
 * <ul>
 * <li>{@link #TraceViewerSwingApp()} \u2014 implements {@link TraceExporter}; register it with
 * {@link org.jopenagent.trace.Tracer#addExporter} to watch traces captured in this same JVM, live, as
 * they complete.</li>
 * <li>{@link #TraceViewerSwingApp(String)} \u2014 point it at a running {@code TraceViewerServer}'s
 * base URL (e.g. {@code "http://localhost:5001"}) to browse traces from a separate process, polling
 * {@code /api/traces}/{@code /api/trace} over {@code java.net.http} every few seconds.</li>
 * </ul>
 */
public final class TraceViewerSwingApp implements TraceExporter {

    private final String remoteBaseUrl;
    private final HttpClient httpClient;
    private final Map<String, JSONObject> fullTraceById = new LinkedHashMap<>();

    private JFrame frame;
    private DefaultListModel<TraceSummary> listModel;
    private JList<TraceSummary> traceList;
    private DefaultTreeModel treeModel;
    private JTree spanTree;
    private DefaultTableModel attributesTableModel;
    private JLabel statusLabel;
    private Timer pollTimer;

    public TraceViewerSwingApp() {
        this(null);
    }

    public TraceViewerSwingApp(String remoteBaseUrl) {
        this.remoteBaseUrl = remoteBaseUrl;
        this.httpClient = remoteBaseUrl != null ? HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build() : null;
    }

    /** Live mode: caches {@code rootSpan} (as JSON, via {@link JsonTraceExporter#toJson(Span)}) and schedules its summary row onto the Swing event thread so the trace list updates as soon as it's built. */
    @Override
    public void export(Span rootSpan) {
        JSONObject json = JsonTraceExporter.toJson(rootSpan);
        TraceSummary summary = new TraceSummary(rootSpan.getId(), rootSpan.getName(), rootSpan.durationMillis(), rootSpan.getStatus());
        fullTraceById.put(rootSpan.getId(), json);
        SwingUtilities.invokeLater(new AddSummaryTask(summary));
    }

    /** Builds and shows the window. Safe to call from any thread. */
    public void show() {
        SwingUtilities.invokeLater(new BuildUiTask());
    }

    /** Stops polling (remote mode) and closes the window. */
    public void dispose() {
        if (pollTimer != null) {
            pollTimer.stop();
        }
        if (frame != null) {
            frame.dispose();
        }
    }

    /** Test-only hook into the captured-trace count; not part of the public API. */
    int fullTraceByIdSizeForTest() {
        return fullTraceById.size();
    }

    private void buildUi() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // fall back to the default cross-platform look and feel
        }

        frame = new JFrame("jOpenAgent Trace Viewer" + (remoteBaseUrl != null ? " \u2014 " + remoteBaseUrl : " (live)"));
        frame.setDefaultCloseOperation(remoteBaseUrl != null ? JFrame.DISPOSE_ON_CLOSE : JFrame.HIDE_ON_CLOSE);
        frame.setSize(1100, 700);
        frame.setLocationByPlatform(true);
        frame.addWindowListener(new StopPollingOnClose());

        listModel = new DefaultListModel<>();
        // Traces captured (via export()) before this window was built are already sitting in
        // fullTraceById; without this, they'd never appear (their AddSummaryTask ran before
        // listModel existed and quietly no-opped).
        for (Map.Entry<String, JSONObject> entry : fullTraceById.entrySet()) {
            listModel.addElement(summaryFromJson(entry.getKey(), entry.getValue()));
        }
        traceList = new JList<>(listModel);
        traceList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        traceList.addListSelectionListener(new TraceSelectionListener());
        JScrollPane listScroll = new JScrollPane(traceList);
        listScroll.setPreferredSize(new Dimension(320, 0));
        listScroll.setBorder(BorderFactory.createTitledBorder("Traces"));

        DefaultMutableTreeNode rootNode = new DefaultMutableTreeNode("(select a trace)");
        treeModel = new DefaultTreeModel(rootNode);
        spanTree = new JTree(treeModel);
        spanTree.setCellRenderer(new SpanTreeCellRenderer());
        spanTree.addTreeSelectionListener(new SpanSelectionListener());
        JScrollPane treeScroll = new JScrollPane(spanTree);
        treeScroll.setBorder(BorderFactory.createTitledBorder("Span tree"));

        attributesTableModel = new NonEditableTableModel(new Object[] { "Attribute", "Value" }, 0);
        JTable attributesTable = new JTable(attributesTableModel);
        JScrollPane attributesScroll = new JScrollPane(attributesTable);
        attributesScroll.setBorder(BorderFactory.createTitledBorder("Attributes"));

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, attributesScroll);
        rightSplit.setResizeWeight(0.65);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightSplit);
        mainSplit.setResizeWeight(0.0);

        statusLabel = new JLabel(remoteBaseUrl != null ? "Connecting to " + remoteBaseUrl + "..." : "Watching live traces in this JVM.");

        JPanel topBar = new JPanel(new BorderLayout());
        if (remoteBaseUrl != null) {
            JButton refreshButton = new JButton("Refresh");
            refreshButton.addActionListener(new RefreshActionListener());
            topBar.add(refreshButton, BorderLayout.EAST);
        }
        topBar.add(statusLabel, BorderLayout.WEST);

        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(topBar, BorderLayout.NORTH);
        frame.getContentPane().add(mainSplit, BorderLayout.CENTER);
        frame.setVisible(true);

        if (remoteBaseUrl != null) {
            refreshFromServer();
            pollTimer = new Timer(3000, new PollActionListener());
            pollTimer.start();
        }
    }

    private void refreshFromServer() {
        new ListTracesWorker().execute();
    }

    private void displayTrace(JSONObject json) {
        JsonTraceNode node = new JsonTraceNode(json);
        DefaultMutableTreeNode root = buildTreeNode(node);
        treeModel.setRoot(root);
        for (int i = 0; i < spanTree.getRowCount(); i++) {
            spanTree.expandRow(i);
        }
        attributesTableModel.setRowCount(0);
    }

    private DefaultMutableTreeNode buildTreeNode(JsonTraceNode node) {
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(node);
        for (JsonTraceNode child : node.getChildren()) {
            treeNode.add(buildTreeNode(child));
        }
        return treeNode;
    }

    private static TraceSummary summaryFromJson(String id, JSONObject json) {
        return new TraceSummary(id, json.optString("name", ""), json.optDouble("durationMillis", -1), json.optString("status", "OK"));
    }

    private boolean listModelContains(String id) {
        for (int i = 0; i < listModel.size(); i++) {
            if (listModel.get(i).id.equals(id)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Exception e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        return cause.getMessage() != null ? cause.getMessage() : cause.toString();
    }

    // ------------------------------------------------------------------
    // Swing plumbing (named classes throughout \u2014 no lambdas)
    // ------------------------------------------------------------------

    /** Lightweight row model for the trace list (id/name/duration/status only); full span-tree JSON is fetched/looked up separately only once a row is selected. */
    private static final class TraceSummary {
        final String id;
        final String name;
        final double durationMillis;
        final String status;

        TraceSummary(String id, String name, double durationMillis, String status) {
            this.id = id;
            this.name = name;
            this.durationMillis = durationMillis;
            this.status = status;
        }

        @Override
        public String toString() {
            String duration = durationMillis >= 0 ? String.format("%.1fms", durationMillis) : "?";
            return name + " (" + duration + ")" + ("ERROR".equals(status) ? " \u26A0" : "");
        }
    }

    /** A {@link DefaultTableModel} whose cells are always read-only, used for the attributes table (which is populated programmatically, never edited by the user). */
    private static final class NonEditableTableModel extends DefaultTableModel {
        private static final long serialVersionUID = 1L;

        NonEditableTableModel(Object[] columnNames, int rowCount) {
            super(columnNames, rowCount);
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }

    /** Renders each {@link JsonTraceNode} in the span tree using its {@link JsonTraceNode#label()}, coloring error spans red when not selected. */
    private static final class SpanTreeCellRenderer extends DefaultTreeCellRenderer {
        private static final long serialVersionUID = 1L;

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus);
            Object userObject = ((DefaultMutableTreeNode) value).getUserObject();
            if (userObject instanceof JsonTraceNode) {
                JsonTraceNode node = (JsonTraceNode) userObject;
                setText(node.label());
                if (node.isError() && !selected) {
                    setForeground(Color.RED);
                }
            }
            return this;
        }
    }

    /** Adds one {@link TraceSummary} to the list model on the Swing event thread (dispatched from {@link #export(Span)}, which runs on whatever thread completed the span). */
    private final class AddSummaryTask implements Runnable {
        private final TraceSummary summary;

        AddSummaryTask(TraceSummary summary) {
            this.summary = summary;
        }

        @Override
        public void run() {
            if (listModel != null && !listModelContains(summary.id)) {
                listModel.addElement(summary);
                statusLabel.setText(listModel.size() + " trace(s) captured");
            }
        }
    }

    /** Runs {@link #buildUi()} on the Swing event thread; the {@link Runnable} passed to {@link SwingUtilities#invokeLater} from {@link #show()}. */
    private final class BuildUiTask implements Runnable {
        @Override
        public void run() {
            buildUi();
        }
    }

    /** Stops the remote-mode poll {@link Timer} when the window closes, so a hidden/disposed frame doesn't keep polling {@link #remoteBaseUrl} in the background. */
    private final class StopPollingOnClose extends WindowAdapter {
        @Override
        public void windowClosing(WindowEvent e) {
            if (pollTimer != null) {
                pollTimer.stop();
            }
        }
    }

    /** Reacts to a trace-list selection: displays the span tree immediately if already cached, otherwise (remote mode) fetches its detail via {@link LoadDetailWorker}. */
    private final class TraceSelectionListener implements ListSelectionListener {
        @Override
        public void valueChanged(ListSelectionEvent e) {
            if (e.getValueIsAdjusting()) {
                return;
            }
            TraceSummary selected = traceList.getSelectedValue();
            if (selected == null) {
                return;
            }
            JSONObject cached = fullTraceById.get(selected.id);
            if (cached != null) {
                displayTrace(cached);
            } else if (remoteBaseUrl != null) {
                statusLabel.setText("Loading trace " + selected.id + "...");
                new LoadDetailWorker(selected.id).execute();
            }
        }
    }

    /** Reacts to a span-tree node selection by populating the attributes table with that {@link JsonTraceNode}'s id/kind/status/duration/error and attribute key-value pairs. */
    private final class SpanSelectionListener implements TreeSelectionListener {
        @Override
        public void valueChanged(TreeSelectionEvent e) {
            Object component = spanTree.getLastSelectedPathComponent();
            if (!(component instanceof DefaultMutableTreeNode)) {
                return;
            }
            Object userObject = ((DefaultMutableTreeNode) component).getUserObject();
            if (!(userObject instanceof JsonTraceNode)) {
                return;
            }
            JsonTraceNode node = (JsonTraceNode) userObject;
            attributesTableModel.setRowCount(0);
            attributesTableModel.addRow(new Object[] { "id", node.getId() });
            attributesTableModel.addRow(new Object[] { "kind", node.getKind() });
            attributesTableModel.addRow(new Object[] { "status", node.getStatus() });
            attributesTableModel.addRow(new Object[] { "durationMillis", node.getDurationMillis() });
            if (node.getErrorMessage() != null) {
                attributesTableModel.addRow(new Object[] { "error", node.getErrorMessage() });
            }
            for (Map.Entry<String, String> attribute : node.getAttributes().entrySet()) {
                attributesTableModel.addRow(new Object[] { attribute.getKey(), attribute.getValue() });
            }
        }
    }

    /** Triggers a manual {@link #refreshFromServer()} in response to the "Refresh" button (remote mode only). */
    private final class RefreshActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            refreshFromServer();
        }
    }

    /** Triggers {@link #refreshFromServer()} periodically; the {@link Timer} listener started in remote mode from {@link #buildUi()}. */
    private final class PollActionListener implements ActionListener {
        @Override
        public void actionPerformed(ActionEvent e) {
            refreshFromServer();
        }
    }

    /** Background {@link SwingWorker} that fetches {@code /api/traces} from {@link #remoteBaseUrl} over {@code java.net.http} and repopulates the trace list on the event thread, preserving the current selection where possible. */
    private final class ListTracesWorker extends SwingWorker<List<TraceSummary>, Void> {
        @Override
        protected List<TraceSummary> doInBackground() throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create(remoteBaseUrl + "/api/traces")).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JSONArray array = (JSONArray) JSON.parse(response.body());
            List<TraceSummary> result = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                result.add(new TraceSummary(item.getString("id"), item.optString("name", ""), item.optDouble("durationMillis", -1), item.optString("status", "OK")));
            }
            return result;
        }

        @Override
        protected void done() {
            try {
                List<TraceSummary> summaries = get();
                TraceSummary previouslySelected = traceList.getSelectedValue();
                listModel.clear();
                for (TraceSummary summary : summaries) {
                    listModel.addElement(summary);
                }
                statusLabel.setText(summaries.size() + " trace(s) on " + remoteBaseUrl);
                if (previouslySelected != null) {
                    restoreSelection(previouslySelected.id);
                }
            } catch (Exception e) {
                statusLabel.setText("Failed to fetch traces: " + rootMessage(e));
            }
        }

        private void restoreSelection(String id) {
            for (int i = 0; i < listModel.size(); i++) {
                if (listModel.get(i).id.equals(id)) {
                    traceList.setSelectedIndex(i);
                    return;
                }
            }
        }
    }

    /** Background {@link SwingWorker} that fetches one trace's full span tree from {@code /api/trace?id=...} on {@link #remoteBaseUrl} and displays it once loaded. */
    private final class LoadDetailWorker extends SwingWorker<JSONObject, Void> {
        private final String id;

        LoadDetailWorker(String id) {
            this.id = id;
        }

        @Override
        protected JSONObject doInBackground() throws Exception {
            String url = remoteBaseUrl + "/api/trace?id=" + URLEncoder.encode(id, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new JSONObject(response.body());
        }

        @Override
        protected void done() {
            try {
                JSONObject detail = get();
                fullTraceById.put(id, detail);
                displayTrace(detail);
                statusLabel.setText("Loaded trace " + id);
            } catch (Exception e) {
                statusLabel.setText("Failed to load trace detail: " + rootMessage(e));
            }
        }
    }
}
