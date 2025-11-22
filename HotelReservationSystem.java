/*
Combined Hotel Reservation System
- Single-file Java program with OOP + JDBC + DAO + Collections + Multithreading + Swing GUI
- Make sure MySQL JDBC driver is in classpath and DB/table exist.
*/

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// ---------------- DB Connection Helper ----------------
class DBConnection {
    private static final String URL = "jdbc:mysql://localhost:3306/hotel_db";
    private static final String USER = "root";
    private static final String PASS = "Aishu@3266";

    static {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("MySQL Driver not found: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }
}

// ---------------- ReservationOperations Interface ----------------
interface ReservationOperations {
    int createReservation(Reservation r) throws SQLException;
    List<Reservation> getAllReservations() throws SQLException;
    Optional<Integer> getRoomNumber(int reservationId, String guestName) throws SQLException;
    boolean updateReservation(int reservationId, Reservation updated) throws SQLException, ReservationNotFoundException;
    boolean deleteReservation(int reservationId) throws SQLException, ReservationNotFoundException;
}

// ---------------- Custom Exception ----------------
class ReservationNotFoundException extends Exception {
    public ReservationNotFoundException(String message) { super(message); }
}

// ---------------- Reservation base class ----------------
class Reservation {
    protected int reservationId; // 0 if not persisted
    protected String guestName;
    protected int roomNumber;
    protected String contactNumber;
    protected Timestamp reservationDate;

    public Reservation(String guestName, int roomNumber, String contactNumber) {
        this.guestName = guestName;
        this.roomNumber = roomNumber;
        this.contactNumber = contactNumber;
        this.reservationDate = new Timestamp(System.currentTimeMillis());
    }

    // getters/setters
    public int getReservationId() { return reservationId; }
    public void setReservationId(int id) { this.reservationId = id; }
    public String getGuestName() { return guestName; }
    public int getRoomNumber() { return roomNumber; }
    public String getContactNumber() { return contactNumber; }
    public Timestamp getReservationDate() { return reservationDate; }

    // polymorphic method
    public double getRate() { return 100.0; }

    @Override
    public String toString() {
        return String.format("Reservation{id=%d, guest='%s', room=%d, contact='%s', date=%s}",
                reservationId, guestName, roomNumber, contactNumber, reservationDate.toString());
    }
}

// ---------------- VIP Reservation (subclass demonstrating inheritance/polymorphism) ----------------
class VIPReservation extends Reservation {
    private String vipLevel;
    public VIPReservation(String guestName, int roomNumber, String contactNumber, String vipLevel) {
        super(guestName, roomNumber, contactNumber);
        this.vipLevel = vipLevel;
    }
    @Override
    public double getRate() {
        switch (vipLevel == null ? "" : vipLevel.toUpperCase()) {
            case "GOLD": return 80.0;
            case "PLATINUM": return 70.0;
            default: return 90.0;
        }
    }
    public String getVipLevel() { return vipLevel; }
    @Override
    public String toString() {
        return String.format("VIPReservation{id=%d, guest='%s', room=%d, vip=%s, date=%s}", reservationId, guestName, roomNumber, vipLevel, reservationDate.toString());
    }
}

// ---------------- DAO Implementation ----------------
class ReservationDAO implements ReservationOperations {

    @Override
    public synchronized int createReservation(Reservation r) throws SQLException {
        String sql = "INSERT INTO reservations (guest_name, room_number, contact_number, reservation_date) VALUES (?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, r.getGuestName());
            ps.setInt(2, r.getRoomNumber());
            ps.setString(3, r.getContactNumber());
            ps.setTimestamp(4, r.getReservationDate());
            int affected = ps.executeUpdate();
            if (affected == 0) throw new SQLException("Creating reservation failed, no rows affected.");
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) { int id = keys.getInt(1); r.setReservationId(id); return id; }
                else throw new SQLException("Creating reservation failed, no ID obtained.");
            }
        }
    }

    @Override
    public synchronized List<Reservation> getAllReservations() throws SQLException {
        List<Reservation> list = new ArrayList<>();
        String sql = "SELECT reservation_id, guest_name, room_number, contact_number, reservation_date FROM reservations ORDER BY reservation_id";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Reservation r = new Reservation(rs.getString("guest_name"), rs.getInt("room_number"), rs.getString("contact_number"));
                r.setReservationId(rs.getInt("reservation_id"));
                r.reservationDate = rs.getTimestamp("reservation_date");
                list.add(r);
            }
        }
        return list;
    }

    @Override
    public synchronized Optional<Integer> getRoomNumber(int reservationId, String guestName) throws SQLException {
        String sql = "SELECT room_number FROM reservations WHERE reservation_id = ? AND guest_name = ?";
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, reservationId);
            ps.setString(2, guestName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return Optional.of(rs.getInt("room_number"));
                else return Optional.empty();
            }
        }
    }

    @Override
    public synchronized boolean updateReservation(int reservationId, Reservation updated) throws SQLException, ReservationNotFoundException {
        String sql = "UPDATE reservations SET guest_name = ?, room_number = ?, contact_number = ? WHERE reservation_id = ?";
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, updated.getGuestName());
            ps.setInt(2, updated.getRoomNumber());
            ps.setString(3, updated.getContactNumber());
            ps.setInt(4, reservationId);
            int aff = ps.executeUpdate();
            if (aff == 0) throw new ReservationNotFoundException("Reservation with id " + reservationId + " not found.");
            return aff > 0;
        }
    }

    @Override
    public synchronized boolean deleteReservation(int reservationId) throws SQLException, ReservationNotFoundException {
        String sql = "DELETE FROM reservations WHERE reservation_id = ?";
        try (Connection conn = DBConnection.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, reservationId);
            int aff = ps.executeUpdate();
            if (aff == 0) throw new ReservationNotFoundException("Reservation with id " + reservationId + " not found.");
            return aff > 0;
        }
    }
}

// ---------------- Reservation Manager ----------------
class ReservationManager {
    private final Map<Integer, Reservation> cache = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ReservationDAO dao = new ReservationDAO();

    // create and cache
    public synchronized int reserve(Reservation r) throws SQLException {
        int id = dao.createReservation(r);
        cache.put(id, r);
        return id;
    }

    // refresh from DB and return list
    public synchronized List<Reservation> viewAll() throws SQLException {
        List<Reservation> fromDb = dao.getAllReservations();
        synchronized (cache) { cache.clear(); for (Reservation r : fromDb) cache.put(r.getReservationId(), r); }
        return fromDb;
    }

    public synchronized Optional<Integer> getRoom(int reservationId, String guestName) throws SQLException {
        Reservation r = cache.get(reservationId);
        if (r != null && r.getGuestName().equals(guestName)) return Optional.of(r.getRoomNumber());
        return dao.getRoomNumber(reservationId, guestName);
    }

    public synchronized boolean update(int reservationId, Reservation updated) throws SQLException, ReservationNotFoundException {
        boolean ok = dao.updateReservation(reservationId, updated);
        if (ok) { updated.setReservationId(reservationId); cache.put(reservationId, updated); }
        return ok;
    }

    public synchronized boolean delete(int reservationId) throws SQLException, ReservationNotFoundException {
        boolean ok = dao.deleteReservation(reservationId);
        if (ok) cache.remove(reservationId);
        return ok;
    }

    public Map<Integer, Reservation> getCacheSnapshot() { synchronized (cache) { return new LinkedHashMap<>(cache); } }
}

// ---------------- AutoSync Thread ----------------
class AutoSyncThread implements Runnable {
    private final ReservationManager manager;
    private volatile boolean running = true;
    private final long intervalMillis;
    public AutoSyncThread(ReservationManager manager, long intervalMillis) { this.manager = manager; this.intervalMillis = intervalMillis; }
    public void stop() { running = false; }
    @Override
    public void run() {
        while (running) {
            try { manager.viewAll(); Thread.sleep(intervalMillis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            catch (SQLException e) { System.err.println("AutoSync error: " + e.getMessage()); }
        }
    }
}

// ---------------- Swing GUI ----------------
class HotelGUI {
    private final ReservationManager manager;
    private final JFrame frame;
    private final DefaultTableModel model;

    public HotelGUI(ReservationManager manager) {
        this.manager = manager;
        frame = new JFrame("Hotel Reservation System");
        model = new DefaultTableModel(new String[]{"Reservation ID", "Guest Name", "Room Number", "Contact", "Date"}, 0);
        createUI();
    }

    private void createUI() {
        frame.setSize(900, 500);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton btnReserve = new JButton("Reserve Room");
        JButton btnView = new JButton("View Reservations");
        JButton btnGet = new JButton("Get Room Number");
        JButton btnUpdate = new JButton("Update Reservation");
        JButton btnDelete = new JButton("Delete Reservation");
        top.add(btnReserve); top.add(btnView); top.add(btnGet); top.add(btnUpdate); top.add(btnDelete);
        frame.add(top, BorderLayout.NORTH);

        JTable table = new JTable(model);
        JScrollPane sp = new JScrollPane(table);
        frame.add(sp, BorderLayout.CENTER);

        btnView.addActionListener(e -> refreshTable());
        btnReserve.addActionListener(e -> openReserveDialog());
        btnGet.addActionListener(e -> openGetRoomDialog());
        btnUpdate.addActionListener(e -> openUpdateDialog());
        btnDelete.addActionListener(e -> openDeleteDialog());

        frame.setVisible(true);
    }

    private void refreshTable() {
        SwingWorker<List<Reservation>, Void> worker = new SwingWorker<List<Reservation>, Void>() {
            @Override
            protected List<Reservation> doInBackground() throws Exception { return manager.viewAll(); }
            @Override
            protected void done() {
                try {
                    List<Reservation> list = get();
                    model.setRowCount(0);
                    for (Reservation r : list) model.addRow(new Object[]{r.getReservationId(), r.getGuestName(), r.getRoomNumber(), r.getContactNumber(), r.getReservationDate().toString()});
                } catch (Exception ex) { JOptionPane.showMessageDialog(frame, "Error refreshing: " + ex.getMessage()); }
            }
        };
        worker.execute();
    }

    private void openReserveDialog() {
        JTextField tName = new JTextField();
        JTextField tRoom = new JTextField();
        JTextField tContact = new JTextField();
        JCheckBox chkVIP = new JCheckBox("VIP");
        JTextField tVIPLevel = new JTextField();
        Object[] form = {"Guest Name:", tName, "Room Number:", tRoom, "Contact:", tContact, chkVIP, "VIP Level:", tVIPLevel};
        int res = JOptionPane.showConfirmDialog(frame, form, "Reserve Room", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            try {
                String name = tName.getText().trim(); int room = Integer.parseInt(tRoom.getText().trim()); String contact = tContact.getText().trim();
                Reservation r = chkVIP.isSelected() ? new VIPReservation(name, room, contact, tVIPLevel.getText().trim()) : new Reservation(name, room, contact);
                int id = manager.reserve(r);
                JOptionPane.showMessageDialog(frame, "Reserved with ID = " + id);
                refreshTable();
            } catch (NumberFormatException ne) { JOptionPane.showMessageDialog(frame, "Invalid number format."); }
            catch (SQLException se) { JOptionPane.showMessageDialog(frame, "DB Error: " + se.getMessage()); }
        }
    }

    private void openGetRoomDialog() {
        JTextField tId = new JTextField(); JTextField tName = new JTextField();
        Object[] form = {"Reservation ID:", tId, "Guest Name:", tName};
        int res = JOptionPane.showConfirmDialog(frame, form, "Get Room Number", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            try {
                int id = Integer.parseInt(tId.getText().trim()); String name = tName.getText().trim();
                Optional<Integer> room = manager.getRoom(id, name);
                if (room.isPresent()) JOptionPane.showMessageDialog(frame, "Room Number: " + room.get());
                else JOptionPane.showMessageDialog(frame, "Reservation not found.");
            } catch (NumberFormatException ne) { JOptionPane.showMessageDialog(frame, "Invalid number format."); }
            catch (SQLException se) { JOptionPane.showMessageDialog(frame, "DB Error: " + se.getMessage()); }
        }
    }

    private void openUpdateDialog() {
        JTextField tId = new JTextField(); JTextField tName = new JTextField(); JTextField tRoom = new JTextField(); JTextField tContact = new JTextField();
        Object[] form = {"Reservation ID:", tId, "New Guest Name:", tName, "New Room Number:", tRoom, "New Contact:", tContact};
        int res = JOptionPane.showConfirmDialog(frame, form, "Update Reservation", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            try {
                int id = Integer.parseInt(tId.getText().trim()); String name = tName.getText().trim(); int room = Integer.parseInt(tRoom.getText().trim()); String contact = tContact.getText().trim();
                Reservation updated = new Reservation(name, room, contact);
                manager.update(id, updated);
                JOptionPane.showMessageDialog(frame, "Updated successfully.");
                refreshTable();
            } catch (NumberFormatException ne) { JOptionPane.showMessageDialog(frame, "Invalid number format."); }
            catch (ReservationNotFoundException rnfe) { JOptionPane.showMessageDialog(frame, rnfe.getMessage()); }
            catch (SQLException se) { JOptionPane.showMessageDialog(frame, "DB Error: " + se.getMessage()); }
        }
    }

    private void openDeleteDialog() {
        JTextField tId = new JTextField(); Object[] form = {"Reservation ID:", tId};
        int res = JOptionPane.showConfirmDialog(frame, form, "Delete Reservation", JOptionPane.OK_CANCEL_OPTION);
        if (res == JOptionPane.OK_OPTION) {
            try {
                int id = Integer.parseInt(tId.getText().trim()); manager.delete(id); JOptionPane.showMessageDialog(frame, "Deleted."); refreshTable();
            } catch (NumberFormatException ne) { JOptionPane.showMessageDialog(frame, "Invalid number format."); }
            catch (ReservationNotFoundException rnfe) { JOptionPane.showMessageDialog(frame, rnfe.getMessage()); }
            catch (SQLException se) { JOptionPane.showMessageDialog(frame, "DB Error: " + se.getMessage()); }
        }
    }
}

// ---------------- Main Application ----------------
public class HotelReservationSystem {
    public static void main(String[] args) {
        // Setup manager and autosync
        ReservationManager manager = new ReservationManager();
        AutoSyncThread autoSync = new AutoSyncThread(manager, 30000); // 30s
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.submit(autoSync);

        // Launch GUI on EDT
        SwingUtilities.invokeLater(() -> new HotelGUI(manager));

        // Add shutdown hook to stop autosync
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            autoSync.stop();
            executor.shutdownNow();
        }));
    }
}
