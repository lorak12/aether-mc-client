import { Component, ReactNode } from "react";

/** Last-resort screen when a page throws while rendering, so the launcher never goes blank. */
export class ErrorBoundary extends Component<{ children: ReactNode }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) {
    return { error };
  }
  render() {
    if (!this.state.error) return this.props.children;
    return (
      <div className="crash-screen" role="alert">
        <h2>Something went wrong on this screen</h2>
        <p className="muted">{this.state.error.message}</p>
        <div className="row">
          <button className="btn" onClick={() => this.setState({ error: null })}>
            Try again
          </button>
          <button className="btn primary" onClick={() => location.reload()}>
            Reload launcher
          </button>
        </div>
      </div>
    );
  }
}
