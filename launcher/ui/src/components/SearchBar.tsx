import { ReactNode, useRef } from "react";
import { IChevronDown, IClose, ISearch, ISort } from "./Icons";
import { Dropdown } from "./Menu";

export interface Choice<T extends string> {
  value: T;
  options: { id: T; label: string; icon?: ReactNode }[];
  onChange: (v: T) => void;
}

/** One visual container: category dropdown, text search, sort dropdown; optional trailing actions outside it. */
export function SearchBar<C extends string, S extends string>({
  category,
  query,
  onQuery,
  placeholder,
  sort,
  trailing,
}: {
  category?: Choice<C>;
  query: string;
  onQuery: (q: string) => void;
  placeholder: string;
  sort?: Choice<S>;
  trailing?: ReactNode;
}) {
  const input = useRef<HTMLInputElement>(null);
  const cat = category?.options.find((o) => o.id === category.value);
  const srt = sort?.options.find((o) => o.id === sort.value);
  return (
    <div className="searchbar-row">
      <div className="searchbar">
        {category && (
          <Dropdown
            className="sb-cat"
            label="Category"
            width={220}
            items={category.options.map((o) => ({ label: o.label, icon: o.icon, checked: o.id === category.value, onSelect: () => category.onChange(o.id) }))}
          >
            {cat?.icon}
            <span>{cat?.label}</span>
            <IChevronDown size={15} />
          </Dropdown>
        )}
        <label className="sb-input">
          <ISearch size={17} />
          <input ref={input} value={query} onChange={(e) => onQuery(e.target.value)} placeholder={placeholder} aria-label={placeholder} />
          {query && (
            <button
              className="sb-clear"
              aria-label="Clear search"
              onClick={() => {
                onQuery("");
                input.current?.focus();
              }}
            >
              <IClose size={14} />
            </button>
          )}
        </label>
        {sort && (
          <Dropdown
            className="sb-sort"
            label="Sort"
            align="end"
            width={210}
            items={[{ heading: "Sort by" }, ...sort.options.map((o) => ({ label: o.label, checked: o.id === sort.value, onSelect: () => sort.onChange(o.id) }))]}
          >
            <ISort size={16} />
            <span>{srt?.label}</span>
            <IChevronDown size={15} />
          </Dropdown>
        )}
      </div>
      {trailing}
    </div>
  );
}

/** Collapsible group of checkbox filters for a filter sidebar. */
export function FilterGroup({ title, children, onClear, active }: { title: string; children: ReactNode; onClear?: () => void; active?: boolean }) {
  return (
    <section className="filter-group">
      <header>
        <h4>{title}</h4>
        {active && onClear && (
          <button className="link sm" onClick={onClear}>
            Clear
          </button>
        )}
      </header>
      <div className="filter-items">{children}</div>
    </section>
  );
}
