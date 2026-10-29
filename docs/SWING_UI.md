# DetWAN Swing experiment runner

The UI is a Java Swing front end for the existing Java experiment in `Main`. It
does not replace the routing, training, admission, or packet-simulation code.
Each seed is launched as a separate Java process through the same `Main`
command-line interface, and its output is streamed into the window.

From the Java project root, compile and launch it with:

```powershell
$sources = rg --files src -g '*.java'
javac -d out $sources
java -cp out DetWanSwingUI
```

The default form is a small development run: 10 selected flows, seed 42, one
training episode, horizon 10, minibatch size 4, and one update round. Change the
values in the form before starting a run. The results root defaults to
`results/swing-runs`; `Main` creates a uniquely named directory for each seed
and refuses to overwrite existing run directories/files.

The live output includes the generated command and `Main`'s logs. An explicit
results root also causes `Main` to export its Java-to-OMNeT++ batch file. The UI
does not start OMNeT++; run the working OMNeT++ simulation separately when you
want to consume that file.

The “Use mapped Table II…” checkbox only selects the paper-mapped budget-level,
PPO clipping, and learning-rate values implemented by `Main`. Episode count and
horizon remain the values entered in the form. The paper's batch-size value 1024
is unresolved and is not inferred by this option. The displayed evaluation is
the existing separate, stochastic final-policy evaluation; it does not update
the model.
