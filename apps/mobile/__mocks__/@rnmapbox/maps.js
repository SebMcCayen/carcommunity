'use strict';
/* global jest */

/**
 * Manual Jest mock for @rnmapbox/maps.
 *
 * Replaces native Mapbox components with lightweight React Native View
 * wrappers so unit tests can render map screens without requiring the
 * native Mapbox SDK.
 *
 * Usage: referenced via moduleNameMapper in package.json jest config.
 */

const React = require('react');
const { View } = require('react-native');

function MapView({ children, ...props }) {
  return React.createElement(View, { testID: 'mapbox-mapview', ...props }, children);
}

function Camera() {
  return null;
}

function PointAnnotation({ children, ...props }) {
  return React.createElement(View, { testID: 'mapbox-point-annotation', ...props }, children);
}

const setAccessToken = jest.fn();

const StyleURL = {
  Street: 'mapbox://styles/mapbox/streets-v11',
  Dark: 'mapbox://styles/mapbox/dark-v10',
  Light: 'mapbox://styles/mapbox/light-v10',
  Satellite: 'mapbox://styles/mapbox/satellite-v9',
  SatelliteStreet: 'mapbox://styles/mapbox/satellite-streets-v11',
  TrafficDay: 'mapbox://styles/mapbox/traffic-day-v2',
  TrafficNight: 'mapbox://styles/mapbox/traffic-night-v2',
};

const MapboxGL = {
  MapView,
  Camera,
  PointAnnotation,
  StyleURL,
  setAccessToken,
};

// Support both `import MapboxGL from '@rnmapbox/maps'` and named imports.
Object.assign(module.exports, MapboxGL);
module.exports.default = MapboxGL;
