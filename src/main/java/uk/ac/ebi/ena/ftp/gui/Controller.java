package uk.ac.ebi.ena.ftp.gui;

import javafx.beans.Observable;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.stage.DirectoryChooser;
import javafx.util.Callback;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.ebi.ena.ftp.gui.custom.ProgressBarTableCell;
import uk.ac.ebi.ena.ftp.gui.custom.MD5TableCell;
import uk.ac.ebi.ena.ftp.model.RemoteFile;
import uk.ac.ebi.ena.ftp.service.WarehouseQuery;
import uk.ac.ebi.ena.ftp.utils.Utils;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class Controller implements Initializable {

    private final static Logger log = LoggerFactory.getLogger(Controller.class);

    @FXML
    private TextField localDownloadDir;

    @FXML
    private Button localDownloadDirBtn, selectAllBtn, startDownloadBtn, stopDownloadBtn;

    @FXML
    private TableView<RemoteFile> fastqFileTable, submittedFileTable;

    @FXML
    private Label selectionLabel;

    @FXML
    private TabPane fileTabPane;

    @FXML
    private ObservableList<RemoteFile> fastqFiles, submittedFiles;

    private List<RemoteFile> notDoneFiles;
    private List<Task> downloadTasks;
    private ExecutorService executor;
    private Controller self = this;
    private long totalSize;


    @Override // This method is called by the FXMLLoader when initialization is complete
    public void initialize(URL fxmlFileLocation, ResourceBundle resources) {
        log.debug("initialize");
        assert startDownloadBtn != null : "fx:id=\"startDownloadBtn\" was not injected: check your FXML file 'gui.fxml'.";
        // initialize your logic here: all @FXML variables will have been injected
//        log.debug("named ------------------" + StringUtils.join(Main.parameters.getNamed(), ","));
//        log.debug("unnamed ------------------" + StringUtils.join(Main.parameters.getUnnamed(), ","));
//        log.debug("raw ------------------" + StringUtils.join(Main.parameters.getRaw(), ","));
        String accession = Main.parameters.getUnnamed().size() > 0 ? Main.parameters.getUnnamed().get(0) : Main.parameters.getNamed().get("accession");
//        if (StringUtils.isBlank(accession)) {
//            accession = "SRX1683606";
//        }
        setupDownloadDirBtn();
        setupTables(accession);
        setupSelectAllBtn();
        setupDownloadButtons();
        updateSelectionMessage();
    }

    private void setupTables(String accession) {
        WarehouseQuery warehouseQuery = new WarehouseQuery();
        List<RemoteFile> queryFastq = warehouseQuery.query(accession, "fastq");
        fastqFiles = FXCollections.observableArrayList(new Callback<RemoteFile, Observable[]>() {
            @Override
            public Observable[] call(RemoteFile param) {
                return new Observable[]{param.downloadProperty()};
            }
        });
        fastqFiles.addAll(queryFastq);
        setupTable(fastqFileTable, fastqFiles);
        List<RemoteFile> querySubmitted = warehouseQuery.query(accession, "submitted");
        submittedFiles = FXCollections.observableArrayList(new Callback<RemoteFile, Observable[]>() {
            @Override
            public Observable[] call(RemoteFile param) {
                return new Observable[]{param.downloadProperty()};
            }
        });
        submittedFiles.addAll(querySubmitted);
        setupTable(submittedFileTable, submittedFiles);
    }

    private void setupTable(TableView<RemoteFile> tableView, ObservableList<RemoteFile> tableFiles) {

        ObservableList<TableColumn<RemoteFile, ?>> columns =
                tableView.getColumns();
        TableColumn<RemoteFile, Boolean> downloadColumn = (TableColumn<RemoteFile, Boolean>) columns.get(0);
        downloadColumn.setCellValueFactory(
                new Callback<TableColumn.CellDataFeatures<RemoteFile, Boolean>, ObservableValue<Boolean>>() {
                    @Override
                    public ObservableValue<Boolean> call(TableColumn.CellDataFeatures<RemoteFile, Boolean> p) {
                        return p.getValue().isDownload();
                    }
                });
        downloadColumn.setCellFactory(
                new Callback<TableColumn<RemoteFile, Boolean>, TableCell<RemoteFile, Boolean>>() {
                    @Override
                    public TableCell<RemoteFile, Boolean> call(TableColumn<RemoteFile, Boolean> p) {
                        CheckBoxTableCell<RemoteFile, Boolean> checkBoxTableCell = new CheckBoxTableCell<RemoteFile, Boolean>();
                        return checkBoxTableCell;
                    }
                });
        downloadColumn.setEditable(true);

        final ObservableList<RemoteFile> finalTableFiles = tableFiles;// copying to final var use inside inner class
        tableFiles.addListener(new ListChangeListener<RemoteFile>() {
            @Override
            public void onChanged(Change<? extends RemoteFile> change) {
                updateSelectionMessage();
            }
        });
        tableView.setItems(tableFiles);
        addProgressColumn(tableView);
        addIconColumn(tableView);
    }

    private void addIconColumn(TableView<RemoteFile> tableView) {
        ObservableList columns = tableView.getColumns();

        TableColumn<RemoteFile, String> progressCol = new TableColumn<>("MD5 OK");
        progressCol.setPrefWidth(60);
        progressCol.setResizable(false);
        PropertyValueFactory<RemoteFile, String> progress = new PropertyValueFactory<>("successIcon");
        progressCol.setCellValueFactory(progress);
        progressCol.setCellFactory(new Callback<TableColumn<RemoteFile, String>, TableCell<RemoteFile, String>>() {
            @Override
            public TableCell<RemoteFile, String> call(TableColumn<RemoteFile, String> param) {
                TableCell<RemoteFile, String> cell = new MD5TableCell();
                return cell;
            }
        });
        columns.add(4, progressCol);
    }

    private void addProgressColumn(TableView<RemoteFile> tableView) {
        ObservableList columns = tableView.getColumns();

        TableColumn<RemoteFile, Double> progressCol = new TableColumn<>("Progress");
        progressCol.setPrefWidth(295);
        progressCol.setResizable(false);
        PropertyValueFactory<RemoteFile, Double> progress = new PropertyValueFactory<>("progress");
        progressCol.setCellValueFactory(progress);
        progressCol.setCellFactory(ProgressBarTableCell.<RemoteFile>forTableColumn());
        columns.add(3, progressCol);
    }

    private void updateSelectionMessage() {
        int count = 0;
        long size = 0;
        String type = "";
        int tabIndex = fileTabPane.getSelectionModel().getSelectedIndex();
        ObservableList<RemoteFile> files = null;
        if (tabIndex == 0) {
            files = fastqFiles;
            type = "FASTQ";
        } else {
            files = submittedFiles;
            type = "Submitted";
        }
        for (RemoteFile file : files) {
            if (file.isDownload().get()) {
                count++;
                size += file.getSize();
            }
        }
        selectionLabel.setText(count + " " + type + " files selected. Total size: " + Utils.getHumanReadableSize(size));
        totalSize = size;
    }

    private void setupDownloadButtons() {
        startDownloadBtn.setOnAction(new StartDownloadHandler());
        stopDownloadBtn.setOnAction(new StopDownloadHandler());
        stopDownloadBtn.setDisable(true);
    }

    public class StartDownloadHandler implements EventHandler<ActionEvent> {
        @Override
        public void handle(ActionEvent actionEvent) {
            if (StringUtils.isBlank(localDownloadDir.getText())) {
                selectionLabel.setText("Please select a download location.");
                return;
            }
            File downloadDir = new File(localDownloadDir.getText());
            log.debug("downloadDir.isDirectory():" + downloadDir.isDirectory());
            log.debug("downloadDir.canWrite():" + downloadDir.canWrite());
            if (!downloadDir.isDirectory()/* || !downloadDir.canWrite()*/) {
                selectionLabel.setText("Unable to save to selected download location.");
                return;
            }
            long usableSpace = downloadDir.getUsableSpace();
            if (usableSpace < totalSize) {
                selectionLabel.setText("Not enough space in selected location to save all files. An additional "
                        + Utils.getHumanReadableSize(totalSize - usableSpace) + " is required.");
                return;
            }

            int tabIndex = fileTabPane.getSelectionModel().getSelectedIndex();
            ObservableList<RemoteFile> files = null;
            if (tabIndex == 0) {
                files = fastqFiles;
            } else {
                files = submittedFiles;
            }
            List<RemoteFile> checkedFiles = new ArrayList<RemoteFile>();
            notDoneFiles = new ArrayList<RemoteFile>();

            for (RemoteFile file : files) {
                if (file.isDownload().get()) {
                    if (file.getTransferred() == 0 && file.getProgress() < 1) {
                        file.updateProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                    }
                    file.setSaveLocation(localDownloadDir.getText());
                    checkedFiles.add(file);
                }
            }
            if (checkedFiles.size() == 0) {
                selectionLabel.setText("No files selected for download.");
                return;
            } else {
                updateSelectionMessage();
                for (RemoteFile file : checkedFiles) {
                    if (!file.isDownloaded()) {
//                        file.setFileList(notDoneFiles);
//                        file.setController(self);
                        notDoneFiles.add(file);
                    }
                }
                if (notDoneFiles.isEmpty()) {
                    selectionLabel.setText("All selected files have already been downloaded.");
                    return;
                }
            }
            startDownloadBtn.setDisable(true);
            stopDownloadBtn.setDisable(false);
            executor = Executors.newSingleThreadExecutor();
            downloadTasks = new ArrayList<>();
            for (final RemoteFile file : notDoneFiles) {
                if (file.getTransferred() == 0) {
                    file.updateProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                }
                Task task = new DownloadTask(file);
                Future<?> submit = executor.submit(task);
                downloadTasks.add(task);
                if (downloadTasks.size() == notDoneFiles.size()) {
                    new Thread() {
                        public void run() {
                            while (!file.isDownloaded() && !stopDownloadBtn.isDisabled()) {
                                try {
                                    sleep(500);
                                } catch (InterruptedException e) {
                                    log.warn("Interrupted");
                                }
                            }
                            startDownloadBtn.setDisable(false);
                            stopDownloadBtn.setDisable(true);
                        }
                    }.start();
                }
            }
        }
    }

    private class StopDownloadHandler implements EventHandler<ActionEvent> {
        @Override
        public void handle(ActionEvent actionEvent) {
            startDownloadBtn.setDisable(false);
            stopDownloadBtn.setDisable(true);
            log.debug("Stopping downloads");
            selectionLabel.setText("Downloading stopped by user! Click Start Download to resume.");
            if (executor != null) {
                List<Runnable> runnables = executor.shutdownNow();
            }
            for (int r = 0; r < notDoneFiles.size(); r++) {
                RemoteFile file = notDoneFiles.get(r);
                if (file.getProgress() == ProgressIndicator.INDETERMINATE_PROGRESS) {
                    file.updateProgress(0);
                }
                if (!file.isDownloaded()) {
                    downloadTasks.get(r).cancel();
                }
            }
        }
    }

    private void setupDownloadDirBtn() {
        localDownloadDirBtn.setOnAction(new EventHandler<ActionEvent>() {
            @Override
            public void handle(ActionEvent actionEvent) {
                DirectoryChooser fileChooser = new DirectoryChooser();
                fileChooser.setTitle("Select download location");
                File localDir = fileChooser.showDialog(localDownloadDirBtn.getScene().getWindow());
                if (localDir != null) {
                    localDownloadDir.setText(localDir.getAbsolutePath());
                }
                updateSelectionMessage();
            }
        });
    }

    private void setupSelectAllBtn() {
        selectAllBtn.setOnAction(new SelectAllHandler());
    }

    private class SelectAllHandler implements EventHandler<ActionEvent> {
        @Override
        public void handle(ActionEvent actionEvent) {
            int tabIndex = fileTabPane.getSelectionModel().getSelectedIndex();
            ObservableList<RemoteFile> files = null;
            if (tabIndex == 0) {
                files = fastqFiles;
            } else {
                files = submittedFiles;
            }
            for (RemoteFile file : files) {
                file.downloadProperty().set(true);
            }
            selectAllBtn.setOnAction(new DeselectAllHandler());
            selectAllBtn.setText("Deselect All");
        }
    }

    private class DeselectAllHandler implements EventHandler<ActionEvent> {
        @Override
        public void handle(ActionEvent actionEvent) {
            int tabIndex = fileTabPane.getSelectionModel().getSelectedIndex();
            ObservableList<RemoteFile> files = null;
            if (tabIndex == 0) {
                files = fastqFiles;
            } else {
                files = submittedFiles;
            }
            for (RemoteFile file : files) {
                if (file.downloadProperty().get()) {
                    file.downloadProperty().setValue(false);
                }
            }
            selectAllBtn.setOnAction(new SelectAllHandler());
            selectAllBtn.setText("Select All");
        }
    }

}



