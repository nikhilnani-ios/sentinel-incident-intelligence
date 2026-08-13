"use client";

import { useEffect, useRef, useState } from "react";
import { getToken } from "./auth";
import type { IncidentEvent } from "./types";

interface StreamState {
  connected: boolean;
  lastEvent: IncidentEvent | null;
  events: IncidentEvent[];
}

/**
 * Subscribes to the live incident feed.
 *
 * <p>EventSource cannot set an Authorization header, so the token rides in the query string — the
 * same trade-off the server documents. Reconnection is left to the browser, which already does
 * exponential backoff correctly; re-implementing it here would only be a worse version.
 */
export function useIncidentStream(onEvent?: (event: IncidentEvent) => void): StreamState {
  const [state, setState] = useState<StreamState>({ connected: false, lastEvent: null, events: [] });

  // Kept in a ref so a caller passing an inline arrow function does not tear down the connection on
  // every render.
  const handlerRef = useRef(onEvent);
  handlerRef.current = onEvent;

  useEffect(() => {
    const token = getToken();
    if (!token) return;

    const streamBaseUrl =
      process.env.NEXT_PUBLIC_INCIDENT_API_URL ?? "http://localhost:8083";

    const source = new EventSource(
      `${streamBaseUrl}/v1/streams/incidents?access_token=${encodeURIComponent(token)}`,
    );

    source.addEventListener("connected", () => {
      setState((previous) => ({ ...previous, connected: true }));
    });

    source.addEventListener("incident", (message) => {
      const event = JSON.parse((message as MessageEvent).data) as IncidentEvent;
      handlerRef.current?.(event);
      setState((previous) => ({
        connected: true,
        lastEvent: event,
        events: [event, ...previous.events].slice(0, 50),
      }));
    });

    source.onerror = () => {
      setState((previous) => ({ ...previous, connected: false }));
    };

    return () => source.close();
  }, []);

  return state;
}
