import { registerRootComponent } from 'expo';

// Register the background location task at module scope, before any navigator
// renders. expo-task-manager requires defineTask() to run before any component
// tree mounts. Importing this module satisfies that requirement.
// NOTE: Background location requires a custom development build — not Expo Go.
import './src/session/backgroundLocationTask';

import { AppRoot } from './src/app/App';

registerRootComponent(AppRoot);
