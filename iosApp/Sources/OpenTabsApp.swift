import SwiftUI
import OpenTabsCore

@main
struct OpenTabsApp: App {
    var body: some Scene {
        WindowGroup { ExerciseLibraryView() }
    }
}

struct ExerciseLibraryView: View {
    private let exercises = ExerciseCatalog().exercises()

    var body: some View {
        NavigationStack {
            List(exercises, id: \.id) { exercise in
                NavigationLink(exercise.title) {
                    ExerciseView(exercise: exercise)
                }
            }
            .navigationTitle("My Tabs")
            .safeAreaInset(edge: .bottom) {
                Text("Original OpenTabs exercises • iOS preview")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .padding()
            }
        }
        .preferredColorScheme(.dark)
    }
}

struct ExerciseView: View {
    let exercise: PracticeExercise
    @State private var fontSize = 18.0

    var body: some View {
        ScrollView([.horizontal, .vertical]) {
            Text(exercise.text)
                .font(.system(size: fontSize, design: .monospaced))
                .fixedSize(horizontal: true, vertical: true)
                .textSelection(.enabled)
                .padding()
                .accessibilityIdentifier("tabText")
        }
        .navigationTitle(exercise.title)
        .toolbar {
            ToolbarItemGroup(placement: .bottomBar) {
                Button("Smaller") { fontSize = max(12, fontSize - 2) }
                    .disabled(fontSize <= 12)
                Spacer()
                Button("Larger") { fontSize = min(32, fontSize + 2) }
                    .disabled(fontSize >= 32)
            }
        }
    }
}
