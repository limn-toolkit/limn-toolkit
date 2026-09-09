#!/usr/bin/env python3
"""Asks a running Limn table what libatspi asks a table: the Table and TableCell interfaces.

The Linux sibling of scripts/a11y/macos/axtable.swift and scripts/a11y/windows/walk-the-table.ps1,
and the check ADR 041 §7 owes this bridge. Run it ON THE GUEST, in the session's environment, with
the demo up (`java -jar limn-demo-all.jar --scene table`). It found the one defect the headless
tests could not: a reply signed "(iiii)" where libatspi reads "iiii".

usage: table-check.py
"""
import sys, gi
gi.require_version("Atspi", "2.0")
from gi.repository import Atspi
def find(node, role, depth=0):
    if node.get_role_name() == role: return node
    for i in range(node.get_child_count()):
        r = find(node.get_child_at_index(i), role, depth+1)
        if r: return r
    return None
desk = Atspi.get_desktop(0)
app = None
for i in range(desk.get_child_count()):
    a = desk.get_child_at_index(i)
    if a and "Limn" in (a.get_name() or ""): app = a
table = find(app, "table")
ifaces = table.get_interfaces()
print("table interfaces:", ifaces)
print("NRows/NColumns:", Atspi.Table.get_n_rows(table), Atspi.Table.get_n_columns(table))
print("NSelectedRows:", Atspi.Table.get_n_selected_rows(table), "selected:", Atspi.Table.get_selected_rows(table))
cell = Atspi.Table.get_accessible_at(table, 1, 3)
print("cell(1,3):", cell.get_role_name(), repr(cell.get_name()), cell.get_interfaces())
print("index_at(1,3):", Atspi.Table.get_index_at(table, 1, 3), "row_at_index(8):", Atspi.Table.get_row_at_index(table, 8))
hdr = Atspi.Table.get_column_header(table, 3)
print("column_header(3):", hdr.get_role_name(), repr(hdr.get_name()), "desc:", repr(Atspi.Table.get_column_description(table, 3)))
print("is_row_selected(2):", Atspi.Table.is_row_selected(table, 2), "(0):", Atspi.Table.is_row_selected(table, 0))
tc = cell
print("TableCell position:", Atspi.TableCell.get_position(tc), "span:", Atspi.TableCell.get_row_column_span(tc))
print("TableCell table:", Atspi.TableCell.get_table(tc).get_role_name())
print("TableCell column headers:", [h.get_name() for h in Atspi.TableCell.get_column_header_cells(tc)])
missing = Atspi.Table.get_accessible_at(table, 5000, 0)
print("cell(5000,0):", None if missing is None else (missing.get_role_name(), missing.get_name()))
print("add_row_selection(4):", Atspi.Table.add_row_selection(table, 4))
import time; time.sleep(1)
print("selected after:", Atspi.Table.get_selected_rows(table))
