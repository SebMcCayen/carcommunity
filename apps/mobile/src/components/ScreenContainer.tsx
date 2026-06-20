import { ReactNode } from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';

import { useAppTheme } from '../hooks/useAppTheme';

type ScreenContainerProps = {
  children: ReactNode;
  testID?: string;
  /** Optional pull-to-refresh control passed to the underlying ScrollView. */
  refreshControl?: React.ReactElement;
};

export const ScreenContainer = ({ children, testID, refreshControl }: ScreenContainerProps) => {
  const { theme } = useAppTheme();

  return (
    <ScrollView
      testID={testID}
      style={[styles.screen, { backgroundColor: theme.colors.pageBackground }]}
      refreshControl={refreshControl}
    >
      <View
        style={[
          styles.content,
          {
            gap: theme.spacing[4],
            padding: theme.spacing[4],
            paddingBottom: theme.spacing[8],
          },
        ]}
      >
        {children}
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  content: {},
});
