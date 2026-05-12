---
version: alpha
name: WalkSafe Assist
description: Mobile-first walking assistance UI for blind and low-vision pedestrians.
colors:
  primary: "#10201C"
  secondary: "#4F5E57"
  tertiary: "#BE3D20"
  neutral: "#F7F5EE"
  surface: "#FFFFFF"
  safety: "#0B6B58"
  warning: "#B93422"
  focus: "#2364D2"
typography:
  headline:
    fontFamily: Arial
    fontSize: 28px
    fontWeight: 700
    lineHeight: 1.15
    letterSpacing: 0
  title:
    fontFamily: Arial
    fontSize: 20px
    fontWeight: 700
    lineHeight: 1.25
    letterSpacing: 0
  body:
    fontFamily: Arial
    fontSize: 16px
    fontWeight: 400
    lineHeight: 1.55
    letterSpacing: 0
  label:
    fontFamily: Arial
    fontSize: 14px
    fontWeight: 700
    lineHeight: 1.2
    letterSpacing: 0
rounded:
  sm: 4px
  md: 8px
  lg: 12px
spacing:
  xs: 4px
  sm: 8px
  md: 16px
  lg: 24px
  xl: 32px
components:
  primary-button:
    backgroundColor: "{colors.tertiary}"
    textColor: "{colors.surface}"
  secondary-button:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.secondary}"
  status-panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.primary}"
  page-background:
    backgroundColor: "{colors.neutral}"
    textColor: "{colors.primary}"
  safety-pill:
    backgroundColor: "{colors.safety}"
    textColor: "{colors.surface}"
  warning-box:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.surface}"
  focus-banner:
    backgroundColor: "{colors.focus}"
    textColor: "{colors.surface}"
---

## Overview

WalkSafe Assist should feel calm, direct, and operational. The app is used while walking, so the interface must reduce cognitive load, avoid decorative distractions, and keep the current safety state visible at a glance.

The product is for blind and low-vision pedestrians and for a demo team validating the model pipeline. The first screen is always the assistive walking surface, not a marketing page.

## Colors

Use high-contrast neutrals for the camera shell and information panels. Reserve dark clay red-orange for the most important action or risk state, use green only for healthy sensor or upload states, and use blue only for focus and permission prompts.

Do not build a single-hue interface. Risk, success, focus, and background must be visually distinct.

## Typography

Use system sans-serif type with clear weight contrast. Labels and buttons should remain readable at arm's length on a phone. Letter spacing is always zero to avoid Korean text distortion.

## Layout

Design mobile-first for a portrait phone. The camera preview is the primary surface and should occupy the majority of the first viewport. Keep controls large, stable, and reachable with one hand.

Use an 8px rhythm, with 4px only for small icon gaps. Avoid nested cards. Status areas can be panels, but the page itself should not look like stacked decorative cards.

## Elevation & Depth

Use borders and contrast instead of heavy shadows. The camera overlay must stay legible without blocking the visible walking path.

## Shapes

Use 8px radius for panels and controls. Use 12px only for large bottom sheets or major containers. Avoid pill-shaped controls except for small status indicators.

## Components

Primary actions use the tertiary color with white text. Secondary controls use a neutral surface with a visible border. Icon buttons must include accessible labels and visible focus states.

Detection boxes use a strong warning color for high confidence and a neutral amber tone for lower confidence. Box labels must be compact and should not cover the center of the camera frame.

## Do's and Don'ts

- Do keep the current risk and GPS status visible without scrolling.
- Do make every primary touch target at least 48px tall.
- Do provide screen-reader labels for camera, speech, and report actions.
- Don't use gradient blobs, decorative orbs, or marketing hero sections.
- Don't rely on color alone for risk state.
- Don't interrupt the user with repeated speech for the same detection.
