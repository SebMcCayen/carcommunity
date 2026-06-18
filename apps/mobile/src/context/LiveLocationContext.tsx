import { createContext, ReactNode, useContext } from 'react';

import {
  useLiveLocationSession,
  type UseLiveLocationSessionResult,
} from '../hooks/useLiveLocationSession';

const LiveLocationContext = createContext<UseLiveLocationSessionResult | undefined>(undefined);

type LiveLocationProviderProps = {
  children: ReactNode;
};

/**
 * Provides a single live location session instance to the component tree.
 * Both LiveLocationScreen and MapScreen read from this context so they share
 * the same session state and GPS position without duplicating the hook.
 */
export const LiveLocationProvider = ({ children }: LiveLocationProviderProps) => {
  const value = useLiveLocationSession();
  return <LiveLocationContext.Provider value={value}>{children}</LiveLocationContext.Provider>;
};

export const useLiveLocation = (): UseLiveLocationSessionResult => {
  const context = useContext(LiveLocationContext);
  if (!context) {
    throw new Error('useLiveLocation must be used inside LiveLocationProvider');
  }
  return context;
};
