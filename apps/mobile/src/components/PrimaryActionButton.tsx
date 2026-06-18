import { Pressable, StyleSheet, Text } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type PrimaryActionButtonProps = {
  label: string;
  onPress?: () => void;
  disabled?: boolean;
  testID?: string;
};

/**
 * Full-width, large primary action button.
 * Uses brand gold color and a larger tap target (minHeight 64) for safe
 * driving-friendly interaction.
 */
export const PrimaryActionButton = ({
  label,
  onPress,
  disabled = false,
  testID,
}: PrimaryActionButtonProps) => {
  const { theme } = useAppTheme();

  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      onPress={disabled ? undefined : onPress}
      style={[
        styles.button,
        {
          backgroundColor: theme.colors.brandPrimary,
          borderRadius: theme.radius.lg,
          paddingHorizontal: theme.spacing[6],
          paddingVertical: theme.spacing[4],
          opacity: disabled ? 0.5 : 1,
        },
      ]}
    >
      <Text
        style={[
          styles.label,
          {
            color: theme.colors.textPrimary,
            fontSize: theme.typography.size.titleMd,
            fontWeight: theme.typography.weight.semibold,
          },
        ]}
      >
        {label}
      </Text>
    </Pressable>
  );
};

const styles = StyleSheet.create({
  button: {
    minHeight: 64,
    alignItems: 'center',
    justifyContent: 'center',
  },
  label: {
    textAlign: 'center',
  },
});
