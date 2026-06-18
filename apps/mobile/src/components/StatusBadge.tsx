import { StyleSheet, Text, View } from 'react-native';

type StatusBadgeProps = {
  /** Background/dot color derived from the current theme. */
  color: string;
  /** Human-readable status label. */
  label: string;
  testID?: string;
};

/**
 * A small indicator composed of a colored dot and a status label.
 * The caller is responsible for mapping status → color using theme tokens.
 */
export const StatusBadge = ({ color, label, testID }: StatusBadgeProps) => {
  return (
    <View testID={testID} style={styles.row} accessibilityRole="text">
      <View style={[styles.dot, { backgroundColor: color }]} />
      <Text style={[styles.label, { color }]}>{label}</Text>
    </View>
  );
};

const DOT_SIZE = 10;

const styles = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  dot: {
    width: DOT_SIZE,
    height: DOT_SIZE,
    borderRadius: DOT_SIZE / 2,
    flexShrink: 0,
  },
  label: {
    fontSize: 14,
    fontWeight: '600',
    lineHeight: 20,
  },
});
