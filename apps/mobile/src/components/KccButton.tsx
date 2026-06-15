import { ReactNode } from 'react';
import { Pressable, StyleSheet, Text } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type ButtonVariant = 'primary' | 'secondary' | 'destructive';

type KccButtonProps = {
  label: string;
  onPress?: () => void;
  variant?: ButtonVariant;
  rightSlot?: ReactNode;
  disabled?: boolean;
  testID?: string;
};

export const KccButton = ({
  label,
  onPress,
  variant = 'primary',
  rightSlot,
  disabled = false,
  testID,
}: KccButtonProps) => {
  const { theme } = useAppTheme();

  const variantStyles = {
    primary: {
      backgroundColor: theme.colors.brandPrimary,
      textColor: theme.colors.textPrimary,
    },
    secondary: {
      backgroundColor: theme.colors.surfaceBackground,
      textColor: theme.colors.textPrimary,
    },
    destructive: {
      backgroundColor: theme.colors.statusError,
      textColor: '#FFFFFF',
    },
  }[variant];

  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityState={{ disabled }}
      onPress={disabled ? undefined : onPress}
      style={[
        styles.button,
        {
          backgroundColor: variantStyles.backgroundColor,
          borderColor: theme.colors.borderDefault,
          borderRadius: theme.radius.md,
          minHeight: 48,
          paddingHorizontal: theme.spacing[4],
          paddingVertical: theme.spacing[3],
          opacity: disabled ? 0.5 : 1,
        },
      ]}
    >
      <Text style={[styles.label, { color: variantStyles.textColor }]}>{label}</Text>
      {rightSlot}
    </Pressable>
  );
};

const styles = StyleSheet.create({
  button: {
    borderWidth: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  label: {
    fontSize: 16,
    fontWeight: '600',
  },
});
