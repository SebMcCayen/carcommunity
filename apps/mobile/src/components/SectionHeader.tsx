import { StyleSheet, Text, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type SectionHeaderProps = {
  title: string;
  testID?: string;
};

export const SectionHeader = ({ title, testID }: SectionHeaderProps) => {
  const { theme } = useAppTheme();

  return (
    <View testID={testID} style={styles.container}>
      <Text
        style={[
          styles.title,
          {
            color: theme.colors.textSecondary,
            fontSize: theme.typography.size.caption,
            letterSpacing: 0.6,
          },
        ]}
      >
        {title.toUpperCase()}
      </Text>
    </View>
  );
};

const styles = StyleSheet.create({
  container: {
    paddingBottom: 2,
  },
  title: {
    fontWeight: '600',
    lineHeight: 16,
  },
});
