export type NavigationRequestLane = "route" | "destination";

export type NavigationRequestToken = {
  readonly lane: NavigationRequestLane;
  readonly sequence: number;
  readonly controller: AbortController;
};

type RequestLaneState = {
  sequence: number;
  current: NavigationRequestToken | null;
};

/** Owns the two independent navigation request lanes and rejects stale completions. */
export class NavigationRequestCoordinator {
  private readonly lanes: Record<NavigationRequestLane, RequestLaneState> = {
    route: { sequence: 0, current: null },
    destination: { sequence: 0, current: null }
  };

  beginRoute(): NavigationRequestToken {
    return this.begin("route");
  }

  beginDestination(): NavigationRequestToken {
    return this.begin("destination");
  }

  isCurrent(token: NavigationRequestToken): boolean {
    const lane = this.lanes[token.lane];
    return lane.current === token && lane.sequence === token.sequence && !token.controller.signal.aborted;
  }

  finish(token: NavigationRequestToken): boolean {
    const lane = this.lanes[token.lane];
    if (lane.current !== token || lane.sequence !== token.sequence) {
      return false;
    }
    lane.current = null;
    return true;
  }

  cancelRoute(): void {
    this.cancel("route");
  }

  cancelDestinationSearch(): void {
    this.cancel("destination");
  }

  cancelDestinationAndNavigation(): void {
    this.cancel("destination");
    this.cancel("route");
  }

  get routeInFlight(): boolean {
    return this.lanes.route.current !== null;
  }

  get destinationInFlight(): boolean {
    return this.lanes.destination.current !== null;
  }

  private begin(laneName: NavigationRequestLane): NavigationRequestToken {
    const lane = this.lanes[laneName];
    lane.current?.controller.abort();
    lane.sequence += 1;
    const token: NavigationRequestToken = {
      lane: laneName,
      sequence: lane.sequence,
      controller: new AbortController()
    };
    lane.current = token;
    return token;
  }

  private cancel(laneName: NavigationRequestLane): void {
    const lane = this.lanes[laneName];
    lane.sequence += 1;
    lane.current?.controller.abort();
    lane.current = null;
  }
}
