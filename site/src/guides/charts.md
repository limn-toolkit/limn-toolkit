---
title: "Charts"
description: "Bars, lines and donuts: data widgets that lay out, theme and clip like every other widget."
---

Three chart widgets ship with the toolkit: `BarChart`, `LineChart` and `DonutChart`. They
are widgets in the ordinary sense (a scroll view clips them, a column stretches them, a
stack draws over them), and they read their type and their surfaces from the theme like
everything else.

## Data

A chart is a list of category labels and one or more series over them. Series carry values,
not points, so the *n*-th value belongs to the *n*-th label:

```java
BarChart revenue = BarChart.of(List.of("Q1", "Q2", "Q3", "Q4"),
        ChartSeries.of("Direct", 120, 145, 132, 168),
        ChartSeries.of("Partner", 80, 92, 105, 99));
```

`addSeries`, `removeSeries` and `setValues` all take effect immediately: a chart bound to
live data is a `setValues` on a timer, not a rebuild.

**A gap is `Double.NaN`.** A line breaks there rather than drawing through zero, which is
the difference between "we have no reading" and "the reading was nothing".

## Bars

```java
chart.setStacked(true);     // series stack instead of sitting side by side
chart.setHorizontal(true);  // categories run down the side
chart.setBarRadius(4);
```

Turned sideways, the category labels move into their own gutter and thin out rather than
overlapping, so you do not have to decide how many fit.

## Lines

```java
LineChart latency = LineChart.of(days,
        ChartSeries.of("p50", 24, 21, 26, 30, 28, 25, 27),
        ChartSeries.of("p99", 62, 58, 71, 88, 74, 66, 70));
latency.setSmooth(true).setArea(true);
```

`setSmooth` curves between points and `setTension` controls how much; `setArea` fills under
the line. Both are per chart, not per series.

## Donuts

```java
DonutChart traffic = DonutChart.of(List.of("Direct", "Search", "Social", "Mail"),
        42, 31, 18, 9);
traffic.setInnerRadius(0.62f);
```

The hole is a **slot, not a hole**: `setCenter(widget)` puts any widget in it, laid out and
drawn like any other child. A total, a label, a column of both: it is the same widget tree
as the rest of your screen.

```java
Column centre = new Column();
centre.gap(2).crossAlignment(Flex.CrossAlignment.CENTER);
centre.add(new Label("100").setRole(Label.Role.TITLE));
centre.add(new Label("sessions").setMuted(true));
traffic.setCenter(centre);
```

## Axes, legend and colour

Both cartesian charts expose their two axes:

```java
chart.valueAxis().setBeginAtZero(true).setTickCount(5).setFormat(v -> v + " ms");
chart.categoryAxis().setTitle("Week");
```

The legend is a real part of the widget: `setLegendPosition(…)` moves it, and
`setLegendInteractive(true)` lets a reader click a series to hide it.

Series colours come from a `ChartPalette`, and the default one is chosen for the surface the
chart is sitting on:

```java
chart.setPalette(ChartPalette.of(brandBlue, brandTeal, brandAmber));
```

A palette that runs out of colours cycles, so a chart never draws two adjacent series in
nothing at all. Give a single series its own colour with `ChartSeries.setColor(…)` when it
means something specific: a threshold, a target, last year.

:::tip[Charts are not a dashboard framework]
There is no data-binding layer and no query language: you hold the numbers, you set them,
the widget draws them. That is the whole contract, and it is why a chart costs the same as
a label to put on a screen.
:::
