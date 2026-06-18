import { ReactNode } from 'react';
import { StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type HomeCardProps = {
  title: string;
  body: string;
  /** Optional leading badge or icon slot rendered above the title. */
  badge?: ReactNode;
  /** Optional footer slot (e.g. action buttons). */
  footer?: ReactNode;
  /** When true, renders a gold left border to draw attention to the card. */
  accent?: boolean;
  testID?: string;
};

/**
 * Card component for home screen sections.
 * Lighter variant of KccCard with an optional accent border and badge slot.
 */
export const HomeCard = ({ title, body, badge, footer, accent = false, testID }: HomeCardProps) => {
  const { theme } = useAppTheme();

  return (
    <View
      testID={testID}
      style={[
        styles.card,
        {
          backgroundColor: theme.colors.surfaceBackground,
          borderColor: accent ? theme.colors.brandPrimary : theme.colors.borderDefault,
          borderRadius: theme.radius.lg,
          borderLeftColor: accent ? theme.colors.brandPrimary : theme.colors.borderDefault,
          borderLeftWidth: accent ? 3 : 1,
          padding: theme.spacing[4],
          gap: theme.spacing[2],
        },
      ]}
    >
      {badge}
      <Text style={[styles.title, { color: theme.colors.textPrimary }]}>{title}</Text>
      <Text style={[styles.body, { color: theme.colors.textSecondary }]}>{body}</Text>
      {footer}
    </View>
  );
};

const styles = StyleSheet.create({
  card: {
    borderWidth: 1,
  },
  title: {
    fontSize: 16,
    fontWeight: '600',
    lineHeight: 22,
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
  },
});
