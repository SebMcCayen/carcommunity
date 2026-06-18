import { StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type LockedFeatureNoticeProps = {
  /** Explanation shown to the user about why the feature requires membership. */
  message: string;
  testID?: string;
};

/**
 * A subtle notice banner indicating that a feature requires an active membership.
 * Shown to free users beneath locked-feature areas.
 */
export const LockedFeatureNotice = ({ message, testID }: LockedFeatureNoticeProps) => {
  const { theme } = useAppTheme();

  return (
    <View
      testID={testID}
      style={[
        styles.container,
        {
          backgroundColor: theme.colors.subtleBackground,
          borderColor: theme.colors.borderDefault,
          borderRadius: theme.radius.md,
          padding: theme.spacing[3],
        },
      ]}
      accessibilityRole="text"
    >
      <Text style={[styles.message, { color: theme.colors.textSecondary }]}>{message}</Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    borderWidth: 1,
  },
  message: {
    fontSize: 13,
    lineHeight: 19,
  },
});
