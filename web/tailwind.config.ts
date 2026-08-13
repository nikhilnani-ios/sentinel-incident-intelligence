import type { Config } from "tailwindcss";

/**
 * The palette is an instrument panel read in a dark room, not a marketing site: near-black
 * backgrounds so the severity colours are the only things that pull the eye, and a single accent
 * reserved for traces and live state. Anything decorative competes with the one red dot that
 * matters at 3am.
 */
const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: {
          900: "#0B0E14",
          800: "#131823",
          700: "#1D2433",
          600: "#2A3347",
        },
        paper: "#E6EAF2",
        muted: "#8A93A6",
        critical: "#F2545B",
        high: "#F2A65A",
        medium: "#D9C55A",
        low: "#6E9BF7",
        ok: "#4CC38A",
        trace: "#6E9BF7",
      },
      fontFamily: {
        display: ["Archivo", "system-ui", "sans-serif"],
        sans: ["'IBM Plex Sans'", "system-ui", "sans-serif"],
        mono: ["'IBM Plex Mono'", "ui-monospace", "monospace"],
      },
      letterSpacing: {
        tightest: "-0.03em",
      },
      keyframes: {
        pulseRing: {
          "0%": { transform: "scale(0.9)", opacity: "0.8" },
          "70%": { transform: "scale(1.6)", opacity: "0" },
          "100%": { transform: "scale(1.6)", opacity: "0" },
        },
        sweep: {
          "0%": { transform: "translateX(-100%)" },
          "100%": { transform: "translateX(200%)" },
        },
      },
      animation: {
        "pulse-ring": "pulseRing 2s cubic-bezier(0.4, 0, 0.6, 1) infinite",
        sweep: "sweep 2.4s linear infinite",
      },
    },
  },
  plugins: [],
};

export default config;
