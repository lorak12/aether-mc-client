import { ReactNode } from "react";

type P = { size?: number; className?: string };
const make =
  (children: ReactNode, fill = false) =>
  ({ size = 20, className }: P) => (
    <svg
      viewBox="0 0 24 24"
      width={size}
      height={size}
      className={className}
      fill={fill ? "currentColor" : "none"}
      stroke={fill ? "none" : "currentColor"}
      strokeWidth="1.7"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden
    >
      {children}
    </svg>
  );

export const IHome = make(<><path d="M3 11.5 12 4l9 7.5" /><path d="M5.5 10v9.5h13V10" /><path d="M10 19.5v-5h4v5" /></>);
export const IProfiles = make(<><path d="M12 3 3.5 7.5 12 12l8.5-4.5L12 3Z" /><path d="m3.5 12 8.5 4.5 8.5-4.5" /><path d="m3.5 16.5 8.5 4.5 8.5-4.5" /></>);
export const IContent = make(<><path d="M12 3v12" /><path d="m7 10 5 5 5-5" /><path d="M4 17v2.5h16V17" /></>);
export const IExplore = make(<><circle cx="12" cy="12" r="9" /><path d="m15.5 8.5-2 5-5 2 2-5 5-2Z" /></>);
export const ICommunity = make(<><circle cx="9" cy="8" r="3.2" /><path d="M3 19.5c.6-3.300 3-5 6-5s5.400 1.700 6 5" /><circle cx="17" cy="9" r="2.500" /><path d="M16 14.600c2.700-.3 4.600 1.300 5 4.400" /></>);
export const INews = make(<><rect x="3.500" y="4.500" width="14" height="15" rx="2" /><path d="M17.500 8.500h3v9a2 2 0 0 1-2 2h-1" /><path d="M7 9h7M7 12.500h7M7 16h4" /></>);
export const IStore = make(<><path d="M5 8h14l-1 12H6L5 8Z" /><path d="M9 10V7a3 3 0 0 1 6 0v3" /></>);
export const ISettings = make(<><circle cx="12" cy="12" r="3" /><path d="M19.400 15a1.700 1.700 0 0 0 .3 1.800l.1.1a2 2 0 1 1-2.800 2.800l-.1-.1a1.700 1.700 0 0 0-1.800-.3 1.700 1.700 0 0 0-1 1.500V21a2 2 0 1 1-4 0v-.1a1.700 1.700 0 0 0-1.100-1.500 1.700 1.700 0 0 0-1.800.3l-.1.1a2 2 0 1 1-2.800-2.800l.1-.1a1.700 1.700 0 0 0 .3-1.800 1.700 1.700 0 0 0-1.500-1H3a2 2 0 1 1 0-4h.1a1.700 1.700 0 0 0 1.500-1.100 1.700 1.700 0 0 0-.3-1.800l-.1-.1a2 2 0 1 1 2.800-2.800l.1.1a1.700 1.700 0 0 0 1.800.3H9a1.700 1.700 0 0 0 1-1.500V3a2 2 0 1 1 4 0v.1a1.700 1.700 0 0 0 1 1.500 1.700 1.700 0 0 0 1.800-.3l.1-.1a2 2 0 1 1 2.800 2.800l-.1.1a1.700 1.700 0 0 0-.3 1.800V9a1.700 1.700 0 0 0 1.500 1H21a2 2 0 1 1 0 4h-.1a1.700 1.700 0 0 0-1.500 1Z" /></>);
export const IPlus = make(<path d="M12 5v14M5 12h14" />);
export const IMinus = make(<path d="M5 12h14" />);
export const ISearch = make(<><circle cx="11" cy="11" r="6.500" /><path d="m20 20-4-4" /></>);
export const IBell = make(<><path d="M6 16.500V11a6 6 0 1 1 12 0v5.500l1.500 2h-15l1.500-2Z" /><path d="M10 20.500a2 2 0 0 0 4 0" /></>);
export const ICoin = make(<><circle cx="12" cy="12" r="8.500" /><path d="M12 7.500v9M9.500 9.800c0-1.100 1.100-1.800 2.500-1.800s2.500.7 2.500 1.800c0 2.500-5 1.500-5 4.200 0 1.100 1.100 1.800 2.500 1.800s2.500-.7 2.500-1.800" /></>);
export const IChevronDown = make(<path d="m6 9 6 6 6-6" />);
export const IChevronRight = make(<path d="m9 6 6 6-6 6" />);
export const IChevronLeft = make(<path d="m15 6-6 6 6 6" />);
export const IArrowLeft = make(<><path d="M19 12H5" /><path d="m11 6-6 6 6 6" /></>);
export const IArrowRight = make(<><path d="M5 12h14" /><path d="m13 6 6 6-6 6" /></>);
export const ILogs = make(<><rect x="3" y="4.500" width="18" height="15" rx="2.500" /><path d="m7 9.500 3 2.500-3 2.500M12.500 15H17" /></>);
export const IClose = make(<path d="M6 6l12 12M18 6 6 18" />);
export const IPlay = make(<path d="M7 4.500v15a1 1 0 0 0 1.500.9l12-7.500a1 1 0 0 0 0-1.700l-12-7.500A1 1 0 0 0 7 4.500Z" />, true);
export const IStop = make(<rect x="6" y="6" width="12" height="12" rx="2" />, true);
export const IDownload = make(<><path d="M12 4v11" /><path d="m7.500 10.500 4.500 4.500 4.500-4.500" /><path d="M5 19.500h14" /></>);
export const IEye = make(<><path d="M2.500 12S6 5.500 12 5.500 21.500 12 21.500 12 18 18.500 12 18.500 2.500 12 2.500 12Z" /><circle cx="12" cy="12" r="2.800" /></>);
export const IFilter = make(<path d="M4 5.500h16l-6 7.500v5l-4 1.500V13L4 5.500Z" />);
export const ISort = make(<><path d="M7 4v16M3.500 16.500 7 20l3.500-3.500" /><path d="M14 6h7M14 11h5M14 16h3" /></>);
export const ICheck = make(<path d="m5 12.500 4.500 4.500L19 7.500" />);
export const IFolder = make(<path d="M3.500 6.500a2 2 0 0 1 2-2h4l2 2.500h7a2 2 0 0 1 2 2v8.500a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-11Z" />);
export const IUpload = make(<><path d="M12 15V4" /><path d="m7.500 8.500 4.500-4.500 4.500 4.500" /><path d="M5 19.500h14" /></>);
export const IRefresh = make(<><path d="M20 11.500A8 8 0 0 0 5.500 7" /><path d="M5 3.500V7.500h4" /><path d="M4 12.500A8 8 0 0 0 18.500 17" /><path d="M19 20.500v-4h-4" /></>);
export const IInfo = make(<><circle cx="12" cy="12" r="9" /><path d="M12 11v5.500M12 7.500v.1" /></>);
export const IHelp = make(<><circle cx="12" cy="12" r="9" /><path d="M9.500 9.500a2.500 2.500 0 1 1 3.500 2.300c-.7.3-1 .9-1 1.700M12 17v.1" /></>);
export const IFile = make(<><path d="M6 3.500h8l4 4v13H6v-17Z" /><path d="M14 3.500v4h4M9 12.500h6M9 16h6" /></>);
export const IShield = make(<path d="M12 3.500 19 6v6c0 4.500-3 7.500-7 8.500-4-1-7-4-7-8.500V6l7-2.500Z" />);
export const IPower = make(<><path d="M12 3.500v8" /><path d="M7 6.500a7.500 7.500 0 1 0 10 0" /></>);
export const IUser = make(<><circle cx="12" cy="8" r="3.800" /><path d="M4.500 20c.8-4 3.700-6 7.500-6s6.700 2 7.500 6" /></>);
export const IUserPlus = make(<><circle cx="10" cy="8" r="3.500" /><path d="M3.500 20c.7-3.800 3.300-5.700 6.500-5.700 1.300 0 2.500.3 3.500.9" /><path d="M18 13v7M14.500 16.500h7" /></>);
export const IMessage = make(<path d="M4 5.500h16v11H9l-5 4v-15Z" />);
export const ISend = make(<><path d="M21 3.500 10 14.500" /><path d="m21 3.500-7 17-3.500-6-6-3.500 16.500-7.500Z" /></>);
export const IMore = make(<><circle cx="5.500" cy="12" r="1.300" /><circle cx="12" cy="12" r="1.300" /><circle cx="18.500" cy="12" r="1.300" /></>, true);
export const ITrash = make(<><path d="M4.500 7h15M9.500 7V4.500h5V7M6.500 7l1 13h9l1-13" /></>);
export const ICopy = make(<><rect x="8.500" y="8.500" width="11" height="11" rx="2" /><path d="M15.500 8.500v-3a1 1 0 0 0-1-1h-9a1 1 0 0 0-1 1v9a1 1 0 0 0 1 1h3" /></>);
export const IExternal = make(<><path d="M14 4.500h5.500V10" /><path d="M19.500 4.500 11 13" /><path d="M18 14v5.500H4.500V6H10" /></>);
export const IStar = make(<path d="m12 3.500 2.600 5.500 6 .7-4.500 4.100 1.200 5.900L12 16.800 6.700 19.700l1.200-5.900L3.400 9.700l6-.7L12 3.500Z" />);
export const IHeart = make(<path d="M12 19.500s-7.500-4.500-7.500-10A4.200 4.200 0 0 1 12 7a4.200 4.200 0 0 1 7.500 2.500c0 5.500-7.500 10-7.500 10Z" />);
export const ISignal = make(<path d="M5 19v-3M9.500 19v-6M14 19v-9M18.500 19V6" />);
export const IGlobe = make(<><circle cx="12" cy="12" r="9" /><path d="M3 12h18M12 3c2.600 2.600 3.900 5.600 3.900 9s-1.300 6.400-3.900 9c-2.600-2.600-3.900-5.600-3.900-9S9.400 5.600 12 3Z" /></>);
export const IPuzzle = make(<path d="M5 8h3.500a2 2 0 1 1 4 0H16v3.500a2 2 0 1 1 0 4V19H12.500a2 2 0 1 0-4 0H5v-3.500a2 2 0 1 0 0-4V8Z" />);
export const IPalette = make(<><path d="M12 3.500a8.500 8.500 0 1 0 0 17c1.200 0 1.800-.8 1.800-1.700 0-1.300-1.100-1.600-1.100-2.700 0-1 .8-1.600 1.800-1.600h2.100c2.200 0 3.900-1.700 3.900-3.900 0-4-3.800-7.100-8.500-7.100Z" /><circle cx="7.500" cy="11" r="1" /><circle cx="10" cy="7.500" r="1" /><circle cx="14.500" cy="7.500" r="1" /></>);
export const ISun = make(<><circle cx="12" cy="12" r="4" /><path d="M12 2.500v2M12 19.500v2M4.500 4.500l1.400 1.400M18.100 18.100l1.400 1.400M2.500 12h2M19.500 12h2M4.500 19.500l1.400-1.400M18.100 5.900l1.400-1.400" /></>);
export const IDatabase = make(<><ellipse cx="12" cy="6" rx="7" ry="2.800" /><path d="M5 6v12c0 1.500 3.100 2.800 7 2.800s7-1.300 7-2.800V6" /><path d="M5 12c0 1.500 3.100 2.800 7 2.800s7-1.300 7-2.800" /></>);
export const IMap = make(<><path d="M3.500 6.500 9 4l6 2.500L20.500 4v13.500L15 20l-6-2.500-5.500 2.500v-13.500Z" /><path d="M9 4v13.500M15 6.500V20" /></>);
export const IServer = make(<><rect x="3.500" y="4" width="17" height="7" rx="2" /><rect x="3.500" y="13" width="17" height="7" rx="2" /><path d="M7 7.500h.1M7 16.500h.1" /></>);
export const ICamera = make(<><path d="M3.500 8.500a2 2 0 0 1 2-2h2l1.500-2h6l1.500 2h2a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2h-13a2 2 0 0 1-2-2v-9Z" /><circle cx="12" cy="12.500" r="3.500" /></>);
export const ISliders = make(<><path d="M4 7h9M17 7h3M4 17h3M11 17h9" /><circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></>);
export const IWarning = make(<><path d="M12 4 21 19.500H3L12 4Z" /><path d="M12 10v4.500M12 17v.1" /></>);
export const IBlock = make(<><circle cx="12" cy="12" r="8.500" /><path d="m6 6 12 12" /></>);
export const ISmile = make(<><circle cx="12" cy="12" r="8.500" /><path d="M8.500 14c.8 1.300 2 2 3.500 2s2.700-.7 3.500-2M9 9.500v.1M15 9.500v.1" /></>);
export const ISparkle = make(<path d="M12 3.500c.6 4.300 2.200 5.900 6.500 6.500-4.300.6-5.900 2.200-6.500 6.500-.6-4.300-2.200-5.900-6.500-6.500 4.300-.6 5.900-2.200 6.500-6.500ZM18.500 15.500c.3 1.900 1 2.600 3 3-2 .3-2.700 1-3 3-.3-2-1-2.700-3-3 2-.4 2.700-1.100 3-3Z" />);
export const ITag = make(<><path d="M3.500 12.500V4.500h8l9 9-8 8-9-9Z" /><circle cx="8" cy="9" r="1.300" /></>);
export const ISidebar = make(<><rect x="3.500" y="4.500" width="17" height="15" rx="2.500" /><path d="M9.500 4.500v15" /></>);
export const IMaximise = make(<rect x="5.500" y="5.500" width="13" height="13" rx="1.500" />);
export const IGift = make(<><rect x="4" y="9" width="16" height="11" rx="1.500" /><path d="M3 9h18M12 9v11M12 9c-1.500-3.500-5.500-4-5.500-1.500S10 9 12 9ZM12 9c1.500-3.500 5.500-4 5.500-1.500S14 9 12 9Z" /></>);
export const IShirt = make(<path d="m8 3.500-5 3 2 4 2.500-1.300V20.500h9V9.200L19 10.500l2-4-5-3c-.5 1.500-2 2.500-4 2.500S8.500 5 8 3.500Z" />);
export const IClock = make(<><circle cx="12" cy="12" r="8.500" /><path d="M12 7.500V12l3 2" /></>);
export const ILink = make(<><path d="M10 14a4 4 0 0 0 5.700 0l3-3a4 4 0 0 0-5.700-5.700l-1 1" /><path d="M14 10a4 4 0 0 0-5.700 0l-3 3a4 4 0 0 0 5.700 5.700l1-1" /></>);
export const IKey = make(<><circle cx="8" cy="15" r="4" /><path d="m11 12 8.500-8.500M16.500 6.500l2.500 2.500M14.500 8.500l2 2" /></>);
