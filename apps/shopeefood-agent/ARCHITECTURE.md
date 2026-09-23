# ShopeeFood agent structure

- `controller/`: Android events / entrypoints.
- `view/`: activities.
- `model/`: order state model.
- `repository/`: local persistence/preferences/log.
- `service/`: Hub, discovery and notification integrations.
- `policy/`: stable automation/risk policy.
- `support/`: parser/accessibility helper.
- `export/`: Excel export.

**r1 guarantee:** only physical file location changed. Java package declaration remains unchanged. Do not combine structural refactor with capture-state-machine changes.
