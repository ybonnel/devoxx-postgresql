package fr.ybonnel;


import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import twitter4j.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DevoxxPostgresMain {

    private static Gson gson = new GsonBuilder().setDateFormat("yyyy-MM-dd'T'HH:mm:ssZ").create();

    private static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ");

    private static AtomicInteger counter = new AtomicInteger(0);

    public static void main(String[] args) throws TwitterException, SQLException, ClassNotFoundException {

        Class.forName("org.postgresql.Driver");

        Connection connection = DriverManager.getConnection("jdbc:postgresql://localhost:5434/devoxx", "devoxx", "devoxx");

        PreparedStatement deleteStatement = connection.prepareStatement("DELETE FROM tweets_devoxx WHERE id = ?");

        PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO tweets_devoxx (id, handle, tweet) VALUES (?, ?, ?::jsonb)");

        Twitter twitter = TwitterFactory.getSingleton();
        Query query = new Query("devoxxfr");
        query.setCount(100);

        QueryResult result;
        do {
            result = twitter.search(query);
            List<Status> tweets = result.getTweets();
            tweets.forEach((tweet) -> DevoxxPostgresMain.processTweet(tweet, preparedStatement, deleteStatement, connection));
        } while ((query = result.nextQuery()) != null);

        connection.close();
    }

    private static void processTweet(Status tweet, PreparedStatement preparedStatement, PreparedStatement deleteStatement, Connection connection) {
        String json = gson.toJson(tweet);
        long id = tweet.getId();
        String handle = tweet.getUser().getScreenName();

        try {
            deleteStatement.setLong(1, id);
            int nb = deleteStatement.executeUpdate();
            preparedStatement.setLong(1, id);
            preparedStatement.setString(2, handle);
            preparedStatement.setString(3, json);
            preparedStatement.executeUpdate();
            if (nb == 0) {
                System.out.println(counter.incrementAndGet() + " - " + sdf.format(tweet.getCreatedAt()) + " : @" + tweet.getUser().getScreenName() + "(" + tweet.getUser().getName() + "):" + tweet.getText());
            } else {
                System.out.println(counter.incrementAndGet() + " - OLD TWEET - " + sdf.format(tweet.getCreatedAt()) + " : @" + tweet.getUser().getScreenName() + "(" + tweet.getUser().getName() + "):" + tweet.getText());
            }
        } catch (SQLException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }


    }
}
