# QuPath Extension: Classify Object Subset

Run a saved [QuPath](https://qupath.github.io/) object classifier on a chosen
subset of objects, instead of every object in the image.

You pick the subset by class, by measurement value, by what you have selected
in the viewer, or any combination of those. The dialog shows a live count of
how many objects will be classified before you click Apply.

Common uses:

- **Stack two classifiers.** Run a CD20 classifier first, then run a CD4/CD8
  classifier only on the cells the first one left unclassified.
- **Pre-filter a noisy image.** Run a strong-marker classifier only on cells
  whose intensity is already above a threshold.
- **Iterate on a small region.** Classify just the objects you have selected
  in the viewer, without touching the rest of the image.

If you have ever used `Classify > Object classification > Apply classifier`
and wished it could target a subset, this extension is the GUI for that.
Every Apply is also recorded as a workflow step, so the same subset operation
can be re-run across a whole project as a script.

This pattern was originally explored in
[Sara McArdle's `B_Helper_Cyto.groovy`](https://github.com/saramcardle/Image-Analysis-Scripts/blob/master/QuPath%20Groovy%20Scripts/Workshop%20Examples/B_Helper_Cyto.groovy)
and discussed in
[this image.sc forum thread](https://forum.image.sc/t/feature-request-apply-classifiers-to-only-some-selected-objects/86383).

![Classify Object Subset dialog: a LUCA composite classifier with Object source set to Custom filter, a multi-class Class filter, a measurement filter ("Cell: Autofluorescence max" greater than 11.0), and the live count "337 of 5,353 objects will be classified", shown over a multiplexed immunofluorescence image.](images/classify-object-subset-dialog.png)

---

## Install

1. Download the extension JAR from the
   [Releases page](https://github.com/uw-loci/qupath-extension-classify-object-subset/releases).
2. Drag the JAR into a running QuPath window. QuPath will offer to copy it
   into your extensions folder; accept.
3. Restart QuPath.

The extension appears under `Extensions > Classify Object Subset`.

**Requires:** QuPath 0.7.0 or later.

---

<details>
<summary><b>Why use this?</b></summary>

QuPath's stock `Object classification > Apply classifier` always runs on
every compatible object in the image. There is no built-in GUI for "apply
this classifier only to cells that are Tumor" or "apply this classifier
only to cells the previous classifier left unclassified". You can do it in
Groovy (see Sara McArdle's `B_Helper_Cyto.groovy`), but only if you are
comfortable writing scripts.

This extension exposes that pattern as a dialog:

- Define the subset declaratively -- by class, by measurement, by current
  selection, or any AND combination.
- See a live `X of Y objects will be classified` preview before you commit.
- Click `Show selection` to highlight the matching objects in the viewer.
- Get a copyable workflow step recorded automatically, so the matching
  operation runs over a whole project via `Run > Run for project`.

If you already have a Groovy snippet that does exactly this, keep using it
-- the scripting API (`ClassifySubsetScripts.runClassifySubset`) is
the same engine the dialog drives, and the recorded workflow steps call
straight into it.

</details>

<details>
<summary><b>How to use it</b></summary>

1. Open a project and the image you want to classify. Make sure the project
   has at least one saved object classifier
   (`Classify > Object classification > Train object classifier`).
2. Open `Extensions > Classify Object Subset > Apply Classification to Subset...`.
3. Pick a classifier from the dropdown. The dialog shows which classes the
   classifier outputs.
4. Choose an object source:
   - **All compatible objects** - whatever the classifier reports as
     compatible with the current image.
   - **Selected objects only** - the current viewer selection, intersected
     with the compatible set.
   - **Custom filter** - applies the class and measurement filters below.
5. (Custom filter only) narrow the set:
   - **Class filter** - tick the checkbox next to each class you want to
     include. Tick `Include unclassified` to also include objects with no
     class.

     On highly multiplexed data the class list runs to dozens of
     combinatorial entries, and there are two controls for that:

     - **Find** - type into the box above the list to show only the classes
       containing that text, then click `Check shown` (or `Uncheck shown`)
       to tick the whole visible group in one action. Classes hidden by the
       search keep whatever state they had, so you can narrow to `CD8`,
       check those, then narrow to `Gzb` and check those too. The counter to
       the right of the list reads `N of M checked` so you can see at a
       glance whether you missed one.
     - **Include derived classes** - tick this and each class you check also
       matches every class built from it. Checking `T cell` then also
       matches `T cell: CD8` and `CD8: T cell`. Component names are matched
       whole, so `CD3` never matches `CD31`. Checking a composite such as
       `T cell: CD8` matches any class containing *both* components, e.g.
       `T cell: CD8: PD1`. Leave it unticked (the default) for exact matches
       only. `Include unclassified` is unaffected by it - unclassified means
       unclassified either way.
   - **Measurement thresholds** - click `Add threshold` to add a row, then
     pick a measurement, an operator (`<`, `<=`, `>`, `>=`, `==`, `!=`,
     `between`), and one (or two) threshold values. Add more rows to gate on
     several measurements at once; the rows are combined with AND, so an
     object must pass every threshold. Each row has its own `x` button to
     remove it.
6. Watch the preview - `X of Y objects will be classified` updates live.
   Click `Show selection` to highlight the matching objects in the viewer.
7. (Optional) tick `Preserve existing class` to leave already-classified
   objects untouched (this passes `resetExistingClass = false` to QuPath's
   classifier).
8. Click `Apply`. The classifier runs on the object subset, the hierarchy
   refreshes, and a workflow step named `Apply classify object subset`
   is appended to the image's workflow history.

**Keyboard shortcuts in the dialog**

- `Enter` -> Apply
- `Esc` -> Close

</details>

<details>
<summary><b>Workflow / scripting</b></summary>

Each time you click Apply, QuPath records the operation as a reusable step
so you can batch the same classification across an entire project later.

After you Apply, open the **Workflow** tab in QuPath and you will see a step
called `Apply classify object subset`. Right-click it and choose
`Create workflow` (or `Create script`) to get a runnable Groovy snippet,
which you can run on every image in the project via `Run > Run for project`.

### Examples

**All compatible objects** (no filter, just classifies what the classifier
considers compatible):

```groovy
import qupath.ext.classifyobjectsubset.scripting.ClassifySubsetScripts

ClassifySubsetScripts.runClassifySubset(
    "MyClassifier",
    [source: "ALL_COMPATIBLE"]
)
```

**Selected objects only** (script reads the current selection at run time):

```groovy
ClassifySubsetScripts.runClassifySubset(
    "MyClassifier",
    [source: "SELECTED_ONLY"]
)
```

**Custom filter** combining class membership and a measurement threshold:

```groovy
ClassifySubsetScripts.runClassifySubset(
    "T-cell-classifier",
    [
        source        : "CUSTOM",
        classes       : [["Tumor"], ["Stroma"], "(unclassified)"],
        measurement   : "DAB: Cell: Mean",
        op            : "LT",
        value1        : 0.25,
        preserveClass : false
    ]
)
```

**Every class built on a marker**, without listing them one by one. With
`includeDerived: true` the entries in `classes` match any classification
containing them, so this reaches `CD3`, `CD3: CD8`, `CD8: CD3: PD1` and so
on. Component names are matched whole rather than as a prefix, so `CD31`
and `CD31: CD8` are left alone:

```groovy
ClassifySubsetScripts.runClassifySubset(
    "Exhaustion-classifier",
    [
        source         : "CUSTOM",
        classes        : [["CD3"]],
        includeDerived : true
    ]
)
```

**Multiple thresholds** combined with AND (e.g. DAB mean above 0.2 *and*
cell area between 50 and 200). Pass a `measurements` list of maps instead of
the flat `measurement`/`op`/`value1` keys:

```groovy
ClassifySubsetScripts.runClassifySubset(
    "T-cell-classifier",
    [
        source       : "CUSTOM",
        measurements : [
            [measurement: "DAB: Cell: Mean", op: "GT", value1: 0.2],
            [measurement: "Cell: Area",      op: "BETWEEN", value1: 50, value2: 200]
        ]
    ]
)
```

**Recreating the `B_Helper_Cyto.groovy` pattern** (apply classifier B only
to what classifier A left unclassified):

```groovy
runObjectClassifier("CD20")  // classifier A - QuPath built-in script API

ClassifySubsetScripts.runClassifySubset(
    "CD4_CD8",  // classifier B
    [
        source : "CUSTOM",
        classes: ["(unclassified)"]
    ]
)
```

### Recognised options

| Key            | Type                | Notes                                                                 |
|----------------|---------------------|-----------------------------------------------------------------------|
| `source`       | `String`            | `"ALL_COMPATIBLE"`, `"SELECTED_ONLY"`, `"CUSTOM"` (required).         |
| `classes`      | `List`              | CUSTOM only. Each entry is either a `List<String>` of component names (recommended; reconstructed via `PathClass.fromCollection`, so colons in derived class chains round-trip safely) or a plain `String` (parsed via `PathClass.fromString`). Use `"(unclassified)"` for null-class. |
| `includeDerived` | `Boolean`         | CUSTOM only. `true` also matches classes derived from those in `classes`, so `"T cell"` matches `"T cell: CD8"` and `"CD8: T cell"`. Names are matched whole (`"CD3"` never matches `"CD31"`). Defaults to `false` (exact matches only). |
| `measurement`  | `String`            | CUSTOM only. Measurement name for a single threshold, as it appears in the measurement table. |
| `op`           | `String`            | One of `LT, LE, GT, GE, EQ, NE, BETWEEN`.                             |
| `value1`       | `Number`            | Primary threshold.                                                    |
| `value2`       | `Number`            | Required only when `op == "BETWEEN"`.                                 |
| `measurements` | `List`              | CUSTOM only. Two or more thresholds, each a `Map` with `measurement`, `op`, `value1`, and (for `BETWEEN`) `value2`. All thresholds - including any flat single one - are AND-combined. |
| `preserveClass`| `Boolean`           | `true` skips overwriting objects that already have a class.           |

Unknown keys are ignored. Missing required keys default to a no-op
(logged as a warning).

</details>

<details>
<summary><b>Behaviour notes</b></summary>

A few things worth knowing once you start using the extension day-to-day:

- **Repeated Apply records repeated workflow steps.** Each click of Apply
  appends a new step to the image's workflow history. That's intentional
  - it preserves the order of operations - but if you only want one
  step, only click Apply once per logical change.
- **Workflow steps reference the classifier by name.** If you retrain or
  rename the classifier later, re-running the recorded script will use
  the *current* classifier with that name, not the one that was active
  when the step was recorded. Save a renamed copy if you need to pin a
  specific version.
- **Undo + workflow history.** QuPath's `Edit > Undo` reverses the
  classification but does **not** remove the recorded workflow step.
  If you undo and don't want the step to re-fire, delete it manually
  from the Workflow tab.
- **`SELECTED_ONLY` is a no-op in batch.** When the recorded script
  runs via `Run > Run for project`, there is no interactive selection,
  so `source: "SELECTED_ONLY"` will classify nothing. The extension
  logs a warning naming the image when this happens.
- **The preview count settles a moment after you stop clicking.** On a
  hierarchy of hundreds of thousands of objects, recounting the subset is
  too slow to run for every individual checkbox click, so the count is
  recomputed once your edits pause (about 150 ms) and the pass itself runs
  off the UI thread. The dialog stays responsive while you tick classes;
  the number catches up right behind you.
- **Switching images closes the dialog.** If you change the active
  image while the dialog is open, the dialog closes itself - this
  prevents accidental Apply against the wrong image's hierarchy.
- **The dialog stays in sync with the hierarchy.** Run a new cell
  detection, apply another classifier, or reset classifications while
  the dialog is open and the universe / class list / measurement list
  refresh automatically.
- **Reserved class name `(unclassified)`.** This exact string is used
  by the recorded scripts to mean "objects without a class". If your
  project genuinely has a class literally named `(unclassified)` it
  will be matched as the null-class sentinel rather than that named
  class - rename it.
- **Multi-part class names round-trip intact.** Each selected class is
  recorded as a list of its component names (e.g. `["Tumor", "Positive"]`
  for `Tumor: Positive`) and reconstructed via `PathClass.fromCollection`,
  so the colon used by QuPath as a parent/child separator never
  ambiguates a re-run. Plain-string entries from hand-edited scripts are
  still accepted and parsed via `PathClass.fromString`.

</details>

<details>
<summary><b>Limitations (v0.1)</b></summary>

- **Project-saved classifiers only.** Loading classifiers from a file path
  outside the project is not yet exposed in the GUI; you can still do it by
  hand from a Groovy script with `loadObjectClassifier("/full/path.json")`.
- **Single classifier per run.** Running several classifiers sequentially
  (composite classifier) requires multiple workflow steps, one per call.
- **AND-only logic between filters.** The class filter and every measurement
  threshold are combined with AND. There is no OR or NOT.
- **Last-used filters are not persisted** between sessions.

These are tracked for follow-up; please file a GitHub issue if you need any
of them sooner.

</details>

<details>
<summary><b>Build from source</b></summary>

```bash
git clone https://github.com/uw-loci/qupath-extension-classify-object-subset
cd qupath-extension-classify-object-subset
./gradlew shadowJar
# JAR appears under build/libs/
```

Requires JDK 21 (set `JAVA_HOME` or pass
`-Dorg.gradle.java.home=/path/to/jdk21` if your default JDK is newer).

Run unit tests with `./gradlew test`. The tests are pure Java and do not
require a running QuPath instance.

</details>

<details>
<summary><b>Contributing</b></summary>

For general support and feature requests, please post on the
[image.sc forum](https://forum.image.sc/) with the `#qupath` tag and
mention `@Mike_Nelson` to flag the topic for my attention.

Bug reports can also be filed via
[GitHub Issues](https://github.com/uw-loci/qupath-extension-classify-object-subset/issues).
Pull requests are welcome - please open an issue first if you are
planning a substantial change so we can discuss scope.

To refresh the dialog screenshot:

1. Open a real image in QuPath inside a project that has at least one
   saved object classifier.
2. Open `Extensions > Classify Object Subset > Apply Classification to Subset...`
   and arrange a representative configuration.
3. Capture the dialog (e.g. with the OS screenshot tool).
4. Save the image as `docs/screenshot-dialog.png` in this repository.

</details>

---

## License

Apache License 2.0. Copyright 2026 Regents of the University of
Wisconsin-Madison. See [LICENSE](LICENSE).
