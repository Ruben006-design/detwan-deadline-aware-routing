import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Swing launcher for the existing Java DetWAN experiment pipeline. */
public final class DetWanSwingUI extends JFrame {
    private static final long serialVersionUID = 1L;
    private final JTextField flowsField = new JTextField("10", 10);
    private final JTextField seedsField = new JTextField("42", 18);
    private final JTextField episodesField = new JTextField("1", 10);
    private final JTextField horizonField = new JTextField("10", 10);
    private final JTextField minibatchField = new JTextField("4", 10);
    private final JTextField updateRoundsField = new JTextField("1", 10);
    private final JTextField budgetLevelsField = new JTextField("16", 10);
    private final JTextField evaluationSeedField = new JTextField("", 12);
    private final JTextField sourceDelayField = new JTextField("0.0", 10);
    private final JTextField destinationDelayField = new JTextField("0.0", 10);
    private final JTextField topologyIdField = new JTextField("abilene-sndlib-12n15l-v1", 28);
    private final JTextField resultsField = new JTextField("results/swing-runs", 32);
    private final JCheckBox paperValuesCheck = new JCheckBox(
            "Use mapped Table II budget / PPO clip / learning-rate values");
    private final JTextArea outputArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Ready");
    private final JButton runButton = new JButton("Run Java experiment");
    private final JButton stopButton = new JButton("Stop");
    private final JButton openResultsButton = new JButton("Open results folder");

    private transient SwingWorker<Integer, String> activeWorker;
    private transient Process activeProcess;
    private volatile Path latestRunDirectory;

    public DetWanSwingUI() {
        super("DetWAN Experiment Runner");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout(10, 10));

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createTitledBorder("Experiment configuration"));
        int row = 0;
        row = addRow(form, row, "Flows to consider", flowsField,
                "Selects the first N requests from the configured Abilene traffic matrix.");
        row = addRow(form, row, "Training seeds", seedsField,
                "Comma-separated; each seed runs independently and receives its own result directory.");
        row = addRow(form, row, "Training episodes", episodesField,
                "Requests are replayed from the finite selected dataset each episode.");
        row = addRow(form, row, "Requests per episode / evaluation horizon", horizonField,
                "Must not exceed the number of selected flows.");
        row = addRow(form, row, "Minibatch size", minibatchField, null);
        row = addRow(form, row, "Update rounds per agent / episode", updateRoundsField, null);
        row = addRow(form, row, "Budget levels (M)", budgetLevelsField,
                "Number of discrete deadline budget levels (paper default is 16; legacy baseline was 5).");
        row = addRow(form, row, "Evaluation seed override (optional)", evaluationSeedField,
                "Blank uses the deterministic seed derived from each training seed.");
        row = addRow(form, row, "Source access delay (ms)", sourceDelayField, null);
        row = addRow(form, row, "Destination access delay (ms)", destinationDelayField, null);
        row = addRow(form, row, "Topology ID", topologyIdField, null);
        row = addPathRow(form, row, "Results root", resultsField);
        GridBagConstraints check = constraints(0, row++);
        check.gridwidth = 3;
        check.anchor = GridBagConstraints.WEST;
        form.add(paperValuesCheck, check);
        paperValuesCheck.setToolTipText("Enables mapped Table II budget-level, PPO clipping, and learning-rate values. "
                + "Episode and horizon settings remain as entered; the batch-size entry 1024 remains unresolved.");

        JLabel note = new JLabel("Runs training → final-policy evaluation → CSV export → Java packet simulation. "
                + "OMNeT++ is a separate step.");
        note.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

        outputArea.setEditable(false);
        outputArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        outputArea.setLineWrap(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputScroll.setBorder(BorderFactory.createTitledBorder("Live run output"));

        JPanel controls = new JPanel(new BorderLayout());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT));
        stopButton.setEnabled(false);
        buttons.add(runButton);
        buttons.add(stopButton);
        JButton clearButton = new JButton("Clear output");
        buttons.add(clearButton);
        buttons.add(openResultsButton);
        controls.add(buttons, BorderLayout.WEST);
        controls.add(statusLabel, BorderLayout.CENTER);

        add(form, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(4, 4));
        center.add(note, BorderLayout.NORTH);
        center.add(outputScroll, BorderLayout.CENTER);
        add(center, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        runButton.addActionListener(event -> startRun());
        stopButton.addActionListener(event -> stopRun());
        clearButton.addActionListener(event -> outputArea.setText(""));
        openResultsButton.addActionListener(event -> openResultsFolder());

        setMinimumSize(new java.awt.Dimension(880, 740));
        setSize(1000, 850);
        setLocationRelativeTo(null);
        append("Project directory: " + Paths.get("").toAbsolutePath());
        append("Start this UI from the project root after compiling: java -cp out DetWanSwingUI");
    }

    private static int addRow(JPanel panel, int row, String label, JTextField field, String tooltip) {
        GridBagConstraints left = constraints(0, row);
        left.anchor = GridBagConstraints.LINE_START;
        panel.add(new JLabel(label + ":"), left);
        GridBagConstraints middle = constraints(1, row);
        middle.fill = GridBagConstraints.HORIZONTAL;
        middle.weightx = 1.0;
        panel.add(field, middle);
        if (tooltip != null) field.setToolTipText(tooltip);
        return row + 1;
    }

    private int addPathRow(JPanel panel, int row, String label, JTextField field) {
        GridBagConstraints left = constraints(0, row);
        left.anchor = GridBagConstraints.LINE_START;
        panel.add(new JLabel(label + ":"), left);
        GridBagConstraints middle = constraints(1, row);
        middle.fill = GridBagConstraints.HORIZONTAL;
        middle.weightx = 1.0;
        panel.add(field, middle);
        JButton browse = new JButton("Browse...");
        browse.addActionListener(event -> chooseResultsDirectory());
        GridBagConstraints right = constraints(2, row);
        panel.add(browse, right);
        return row + 1;
    }

    private static GridBagConstraints constraints(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(3, 8, 3, 8);
        return c;
    }

    private void chooseResultsDirectory() {
        JFileChooser chooser = new JFileChooser(new File(resultsField.getText()).getAbsoluteFile());
        chooser.setDialogTitle("Choose results root directory");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            resultsField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startRun() {
        if (activeWorker != null && !activeWorker.isDone()) return;
        final List<String> command;
        final Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        try {
            validateProjectRoot(projectRoot);
            command = buildCommand();
        } catch (RuntimeException | java.io.IOException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Cannot start experiment",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        append("\n$ " + joinForDisplay(command));
        setRunning(true, "Running...");
        activeWorker = new SwingWorker<Integer, String>() {
            @Override protected Integer doInBackground() throws Exception {
                ProcessBuilder builder = new ProcessBuilder(command);
                builder.directory(projectRoot.toFile());
                builder.redirectErrorStream(true);
                activeProcess = builder.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        activeProcess.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) publish(line);
                }
                return activeProcess.waitFor();
            }

            @Override protected void process(List<String> chunks) {
                for (String line : chunks) {
                    append(line);
                    if (line.startsWith("Run record directory: ")) {
                        String dirStr = line.substring("Run record directory: ".length()).trim();
                        latestRunDirectory = Paths.get(dirStr);
                    }
                }
            }

            @Override protected void done() {
                activeProcess = null;
                try {
                    int exitCode = get();
                    setRunning(false, exitCode == 0 ? "Completed (exit 0)" : "Failed (exit " + exitCode + ")");
                    append("Process exit code: " + exitCode);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    setRunning(false, "Interrupted");
                    append("Run interrupted.");
                } catch (ExecutionException ex) {
                    setRunning(false, "Failed to launch");
                    append("Run failed: " + ex.getCause());
                    JOptionPane.showMessageDialog(DetWanSwingUI.this,
                            String.valueOf(ex.getCause()), "Run failed", JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        activeWorker.execute();
    }

    private List<String> buildCommand() {
        int flowCount = positiveInt(flowsField, "Flows to consider");
        int episodes = positiveInt(episodesField, "Training episodes");
        int horizon = positiveInt(horizonField, "Requests per episode / horizon");
        int minibatch = positiveInt(minibatchField, "Minibatch size");
        int rounds = positiveInt(updateRoundsField, "Update rounds");
        int budgetLevels = positiveInt(budgetLevelsField, "Budget levels (M)");
        if (horizon > flowCount) throw new IllegalArgumentException("Horizon cannot exceed flow count.");
        if (minibatch > horizon) throw new IllegalArgumentException("Minibatch size cannot exceed horizon.");
        double sourceDelay = nonnegativeDouble(sourceDelayField, "Source access delay");
        double destinationDelay = nonnegativeDouble(destinationDelayField, "Destination access delay");
        if (sourceDelay > 1.0 || destinationDelay > 1.0) {
            throw new IllegalArgumentException("Endpoint delays must be at most 1 ms in the current project configuration.");
        }
        String topologyId = topologyIdField.getText().trim();
        if (topologyId.isEmpty()) throw new IllegalArgumentException("Topology ID cannot be empty.");
        String results = resultsField.getText().trim();
        if (results.isEmpty()) throw new IllegalArgumentException("Choose a results directory.");

        List<String> args = new ArrayList<>();
        args.add(javaExecutable());
        args.add("-cp");
        args.add("out");
        args.add("Main");
        args.add("--flows"); args.add(Integer.toString(flowCount));
        args.add("--seeds"); args.add(validateSeeds(seedsField.getText()));
        args.add("--episodes"); args.add(Integer.toString(episodes));
        args.add("--horizon"); args.add(Integer.toString(horizon));
        args.add("--minibatch-size"); args.add(Integer.toString(minibatch));
        args.add("--update-rounds"); args.add(Integer.toString(rounds));
        args.add("--budget-levels"); args.add(Integer.toString(budgetLevels));
        args.add("--source-access-delay-ms"); args.add(Double.toString(sourceDelay));
        args.add("--destination-access-delay-ms"); args.add(Double.toString(destinationDelay));
        args.add("--topology-id"); args.add(topologyId);
        args.add("--results-root"); args.add(Paths.get(results).toAbsolutePath().normalize().toString());
        String eval = evaluationSeedField.getText().trim();
        if (!eval.isEmpty()) {
            try { Long.parseLong(eval); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Evaluation seed must be an integer."); }
            args.add("--evaluation-seed"); args.add(eval);
        }
        if (paperValuesCheck.isSelected()) args.add("--paper-table-ii");
        return args;
    }

    private static String validateSeeds(String text) {
        String[] values = text.split(",");
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            String seed = value.trim();
            if (seed.isEmpty()) throw new IllegalArgumentException("Enter one or more comma-separated integer seeds.");
            try { Long.parseLong(seed); }
            catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid seed: " + seed); }
            normalized.add(seed);
        }
        return String.join(",", normalized);
    }

    private static int positiveInt(JTextField field, String name) {
        try {
            int value = Integer.parseInt(field.getText().trim());
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a positive integer.");
        }
    }

    private static double nonnegativeDouble(JTextField field, String name) {
        try {
            double value = Double.parseDouble(field.getText().trim());
            if (!Double.isFinite(value) || value < 0.0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(name + " must be a finite non-negative number.");
        }
    }

    private static void validateProjectRoot(Path root) throws java.io.IOException {
        if (!Files.isDirectory(root.resolve("dataset"))) {
            throw new java.io.IOException("Run the UI with the Java project root as the working directory. "
                    + "Could not find dataset/ under " + root);
        }
        if (!Files.isRegularFile(root.resolve("out/Main.class"))) {
            throw new java.io.IOException("Compiled classes were not found at out/Main.class. Compile the project first.");
        }
    }

    private static String javaExecutable() {
        String binary = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Paths.get(System.getProperty("java.home"), "bin", binary).toString();
    }

    private static String joinForDisplay(List<String> command) {
        StringBuilder shown = new StringBuilder();
        for (String part : command) {
            if (shown.length() > 0) shown.append(' ');
            shown.append('"').append(part.replace("\"", "\\\"")).append('"');
        }
        return shown.toString();
    }

    private void stopRun() {
        if (activeProcess != null) {
            activeProcess.destroy();
            statusLabel.setText("Stopping...");
        }
    }

    private void openResultsFolder() {
        Path target = latestRunDirectory;
        if (target == null || !Files.isDirectory(target)) {
            String dirText = resultsField.getText().trim();
            if (!dirText.isEmpty()) {
                target = Paths.get(dirText).toAbsolutePath().normalize();
            }
        }
        if (target == null || !Files.exists(target)) {
            JOptionPane.showMessageDialog(this,
                    "Results directory does not exist yet:\n" + target,
                    "Cannot open folder", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            if (java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                java.awt.Desktop.getDesktop().open(target.toFile());
            } else {
                JOptionPane.showMessageDialog(this,
                        "Desktop file manager not supported. Folder location:\n" + target,
                        "Results folder", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this,
                    "Failed to open folder: " + ex.getMessage() + "\nPath: " + target,
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void setRunning(boolean running, String status) {
        runButton.setEnabled(!running);
        stopButton.setEnabled(running);
        statusLabel.setText(status);
    }

    private void append(String line) {
        outputArea.append(line + System.lineSeparator());
        outputArea.setCaretPosition(outputArea.getDocument().getLength());
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            DetWanSwingUI ui = new DetWanSwingUI();
            ui.setVisible(true);
        });
    }
}
